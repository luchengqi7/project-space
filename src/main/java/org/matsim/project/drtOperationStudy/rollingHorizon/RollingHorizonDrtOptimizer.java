package org.matsim.project.drtOperationStudy.rollingHorizon;

import com.google.common.base.Preconditions;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.contrib.drt.extension.preplanned.optimizer.WaitForStopTask;
import org.matsim.contrib.drt.optimizer.DrtOptimizer;
import org.matsim.contrib.drt.optimizer.VehicleEntry;
import org.matsim.contrib.drt.passenger.AcceptedDrtRequest;
import org.matsim.contrib.drt.passenger.DrtRequest;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.schedule.DrtDriveTask;
import org.matsim.contrib.drt.schedule.DrtStayTask;
import org.matsim.contrib.drt.schedule.DrtStopTask;
import org.matsim.contrib.drt.schedule.DrtTaskFactory;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.fleet.Fleet;
import org.matsim.contrib.dvrp.optimizer.Request;
import org.matsim.contrib.dvrp.passenger.PassengerRequestRejectedEvent;
import org.matsim.contrib.dvrp.passenger.PassengerRequestScheduledEvent;
import org.matsim.contrib.dvrp.path.VrpPathWithTravelData;
import org.matsim.contrib.dvrp.path.VrpPaths;
import org.matsim.contrib.dvrp.schedule.*;
import org.matsim.contrib.dvrp.tracker.OnlineDriveTaskTracker;
import org.matsim.contrib.dvrp.util.LinkTimePair;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.mobsim.framework.MobsimTimer;
import org.matsim.core.mobsim.framework.events.MobsimBeforeSimStepEvent;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.router.speedy.SpeedyALTFactory;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;

import java.util.*;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;

import static org.matsim.contrib.drt.schedule.DrtTaskBaseType.STAY;

public class RollingHorizonDrtOptimizer implements DrtOptimizer {
    private final Logger log = LogManager.getLogger(RollingHorizonDrtOptimizer.class);
    private final Network network;
    private final TravelTime travelTime;
    private final MobsimTimer timer;
    private final DrtTaskFactory taskFactory;
    private final EventsManager eventsManager;
    private final ScheduleTimingUpdater scheduleTimingUpdater;
    private final LeastCostPathCalculator router;
    private final double stopDuration;
    private final String mode;
    private final DrtConfigGroup drtCfg;

    private final Fleet fleet;
    private final ForkJoinPool forkJoinPool;
    private final VehicleEntry.EntryFactory vehicleEntryFactory;

    private final Map<Id<Person>, DrtRequest> openRequests = new HashMap<>();

    private final PDPTWSolverJsprit solver; // TODO make an interface for this

    private final double horizon;
    private final double interval; // Must be smaller than or equal to the horizon
    private double serviceStartTime = Double.MAX_VALUE;
    private double serviceEndTime = 0;

    private final List<DrtRequest> prebookedRequests = new ArrayList<>();

    private PreplannedSchedules preplannedSchedules;

    public RollingHorizonDrtOptimizer(DrtConfigGroup drtCfg, Network network, TravelTime travelTime,
                                      TravelDisutility travelDisutility, MobsimTimer timer, DrtTaskFactory taskFactory,
                                      EventsManager eventsManager, Fleet fleet, ScheduleTimingUpdater scheduleTimingUpdater,
                                      ForkJoinPool forkJoinPool, VehicleEntry.EntryFactory vehicleEntryFactory,
                                      PDPTWSolverJsprit pdptwSolverJsprit, Population plans, double horizon, double interval) {
        this.network = network;
        this.travelTime = travelTime;
        this.timer = timer;
        this.taskFactory = taskFactory;
        this.eventsManager = eventsManager;
        this.scheduleTimingUpdater = scheduleTimingUpdater;
        this.solver = pdptwSolverJsprit;
        this.router = new SpeedyALTFactory().createPathCalculator(network, travelDisutility, travelTime);
        this.stopDuration = drtCfg.stopDuration;
        this.fleet = fleet;
        this.forkJoinPool = forkJoinPool;
        this.vehicleEntryFactory = vehicleEntryFactory;
        this.drtCfg = drtCfg;
        this.mode = drtCfg.getMode();
        this.horizon = horizon;
        this.interval = interval;

        readPrebookedRequests(plans);
        initSchedules(fleet);

        assert interval <= horizon : "Interval of optimization must be smaller than or equal to the horizon length!";
    }

    private void readPrebookedRequests(Population plans) {
        for (Person person : plans.getPersons().values()) {
            int counter = 0;
            for (var leg : TripStructureUtils.getLegs(person.getSelectedPlan())) {
                if (!leg.getMode().equals(mode)) {
                    continue;
                }
                var startLink = network.getLinks().get(leg.getRoute().getStartLinkId());
                var endLink = network.getLinks().get(leg.getRoute().getEndLinkId());
                double earliestPickupTime = leg.getDepartureTime().seconds();
                double latestPickupTime = earliestPickupTime + drtCfg.maxWaitTime;
                double estimatedDirectTravelTime = VrpPaths.calcAndCreatePath(startLink, endLink, earliestPickupTime, router, travelTime).getTravelTime();
                double latestArrivalTime = earliestPickupTime + drtCfg.maxTravelTimeAlpha * estimatedDirectTravelTime + drtCfg.maxTravelTimeBeta;
                DrtRequest drtRequest = DrtRequest.newBuilder()
                        .id(Id.create(person.getId().toString() + "_" + counter, Request.class))
                        .submissionTime(earliestPickupTime)
                        .earliestStartTime(earliestPickupTime)
                        .latestStartTime(latestPickupTime)
                        .latestArrivalTime(latestArrivalTime)
                        .passengerId(person.getId())
                        .mode(mode)
                        .fromLink(startLink)
                        .toLink(endLink)
                        .build();
                prebookedRequests.add(drtRequest);
                counter++;
            }
        }
    }

    @Override
    public void requestSubmitted(Request request) {
        DrtRequest drtRequest = (DrtRequest) request;
        openRequests.put(drtRequest.getPassengerId(), drtRequest);

        var preplannedRequest = createFromRequest(drtRequest);
        var vehicleId = preplannedSchedules.preplannedRequestToVehicle.get(preplannedRequest.key);

        if (vehicleId == null) {
            Preconditions.checkState(preplannedSchedules.unassignedRequests.containsKey(preplannedRequest.key),
                    "Pre-planned request (%s) not assigned to any vehicle and not marked as unassigned.",
                    preplannedRequest);
            eventsManager.processEvent(new PassengerRequestRejectedEvent(timer.getTimeOfDay(), mode, request.getId(),
                    drtRequest.getPassengerId(), "Marked as unassigned"));
            return;
        }

        eventsManager.processEvent(
                new PassengerRequestScheduledEvent(timer.getTimeOfDay(), drtRequest.getMode(), drtRequest.getId(),
                        drtRequest.getPassengerId(), vehicleId, Double.NaN, Double.NaN));
        //Current implementation we do not know the scheduled pickup and drop off times
    }

    @Override
    public void nextTask(DvrpVehicle vehicle) {
        scheduleTimingUpdater.updateBeforeNextTask(vehicle);
        var schedule = vehicle.getSchedule();

        if (schedule.getStatus() == Schedule.ScheduleStatus.PLANNED) {
            schedule.nextTask();
            return;
        }

        var currentTask = schedule.getCurrentTask();
        var currentLink = Tasks.getEndLink(currentTask);
        double currentTime = timer.getTimeOfDay();
        var preplannedStopsToVisit = preplannedSchedules.vehicleToPreplannedStops.get(vehicle.getId());
        var nextStop = preplannedStopsToVisit.peek();

        if (nextStop == null) {
            // no preplanned stops for the vehicle within current horizon
            if (currentTime < vehicle.getServiceEndTime()) {
                // fill the time gap with STAY
                schedule.addTask(taskFactory.createStayTask(vehicle, currentTime, vehicle.getServiceEndTime(), currentLink));
            } else if (!STAY.isBaseTypeOf(currentTask)) {
                // we need to end the schedule with STAY task even if it is delayed
                schedule.addTask(taskFactory.createStayTask(vehicle, currentTime, currentTime, currentLink));
            }
        } else if (!nextStop.getLinkId().equals(currentLink.getId())) {
            // Next stop is at another location? --> Add a drive task
            var nextLink = network.getLinks().get(nextStop.getLinkId());
            VrpPathWithTravelData path = VrpPaths.calcAndCreatePath(currentLink, nextLink, currentTime, router,
                    travelTime);
            schedule.addTask(taskFactory.createDriveTask(vehicle, path, DrtDriveTask.TYPE));
        } else if (nextStop.preplannedRequest.earliestStartTime >= timer.getTimeOfDay()) {
            // We are at the stop location. But we are too early. --> Add a wait for stop task
            // Currently assuming the mobsim time step is 1 s
            schedule.addTask(
                    new WaitForStopTask(currentTime, nextStop.preplannedRequest.earliestStartTime + 1, currentLink));
        } else {
            // We are ready for the stop task! --> Add stop task to the schedule
            preplannedStopsToVisit.poll(); //remove this stop from queue
            var stopTask = taskFactory.createStopTask(vehicle, currentTime, currentTime + stopDuration, currentLink);
            if (nextStop.pickup) {
                var request = Preconditions.checkNotNull(openRequests.get(nextStop.preplannedRequest.key.passengerId),
                        "Request (%s) has not been yet submitted", nextStop.preplannedRequest);
                stopTask.addPickupRequest(AcceptedDrtRequest.createFromOriginalRequest(request));
            } else {
                var request = Preconditions.checkNotNull(openRequests.remove(nextStop.preplannedRequest.key.passengerId),
                        "Request (%s) has not been yet submitted", nextStop.preplannedRequest);
                stopTask.addDropoffRequest(AcceptedDrtRequest.createFromOriginalRequest(request));
            }
            schedule.addTask(stopTask);
        }

        // switch to the next task and update currentTasks
        schedule.nextTask();
    }

    @Override
    public void notifyMobsimBeforeSimStep(MobsimBeforeSimStepEvent mobsimBeforeSimStepEvent) {
        double now = mobsimBeforeSimStepEvent.getSimulationTime();
        // TODO at time = 0, vehicle does not have any task, therefore, we can only start at t = 1
        if (now % interval == 1 && now >= serviceStartTime && now < serviceEndTime) {
            for (DvrpVehicle v : fleet.getVehicles().values()) {
                scheduleTimingUpdater.updateTimings(v);
            }

            // Information to be passed to the Solver
            Map<Id<DvrpVehicle>, OnlineVehicleInfo> realTimeVehicleInfoMap = new HashMap<>();

            // Begin reading data
            // Analyze vehicle current information
            var vehicleEntries = forkJoinPool.submit(() -> fleet.getVehicles()
                    .values()
                    .parallelStream()
                    .map(v -> vehicleEntryFactory.create(v, now))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toMap(e -> e.vehicle.getId(), e -> e))).join();
            for (VehicleEntry vehicleEntry : vehicleEntries.values()) {
                Schedule schedule = vehicleEntry.vehicle.getSchedule();
                Task currentTask = schedule.getCurrentTask();

                Link currentLink = null;
                double divertableTime = Double.NaN;

                if (currentTask instanceof DrtStayTask) {
                    currentLink = ((DrtStayTask) currentTask).getLink();
                    divertableTime = now;
                }

                if (currentTask instanceof WaitForStopTask) {
                    currentLink = ((WaitForStopTask) currentTask).getLink();
                    divertableTime = now;
                }

                if (currentTask instanceof DriveTask) {
                    LinkTimePair diversion = ((OnlineDriveTaskTracker) currentTask.getTaskTracker()).getDiversionPoint();
                    currentLink = diversion.link;
                    divertableTime = diversion.time;
                }

                if (currentTask instanceof DrtStopTask) {
                    currentLink = ((DrtStopTask) currentTask).getLink();
                    divertableTime = currentTask.getEndTime();
                }

                Preconditions.checkState(currentLink != null, "Current link should not be null! Vehicle ID = " + vehicleEntry.vehicle.getId().toString());
                Preconditions.checkState(!Double.isNaN(divertableTime), "Divertable time should not be NaN! Vehicle ID = " + vehicleEntry.vehicle.getId().toString());
                OnlineVehicleInfo onlineVehicleInfo = new OnlineVehicleInfo(vehicleEntry.vehicle, currentLink, divertableTime);
                realTimeVehicleInfoMap.put(vehicleEntry.vehicle.getId(), onlineVehicleInfo);
            }

            // Read new requests
            List<DrtRequest> newRequests = readRequestsFromTimeBin(now);

            // Calculate the new preplanned schedule
            double endTime = now + horizon;
            log.info("Calculating the plan for t =" + now + " to t = " + endTime);
            log.info("There are " + newRequests.size() + " new request within this horizon");
            preplannedSchedules = solver.calculate(preplannedSchedules, realTimeVehicleInfoMap, newRequests);

            // Update vehicles schedules
            for (OnlineVehicleInfo onlineVehicleInfo : realTimeVehicleInfoMap.values()) {
                DvrpVehicle vehicle = onlineVehicleInfo.vehicle;
                Schedule schedule = vehicle.getSchedule();
                Task currentTask = schedule.getCurrentTask();
                Link currentLink = onlineVehicleInfo.currentLink;
                double divertableTime = onlineVehicleInfo.divertableTime;

                // "Stay" task or "Wait for stop" task --> end now (then vehicle will find next task in the nextTask section)
                if (currentTask instanceof DrtStayTask) {
                    currentTask.setEndTime(now);
                }

                if (currentTask instanceof WaitForStopTask) {
                    currentTask.setEndTime(now);
                }

                // Drive task --> check if diversion is needed
                if (currentTask instanceof DrtDriveTask) {
                    var stopsToVisit = preplannedSchedules.vehicleToPreplannedStops.get(vehicle.getId());
                    if (stopsToVisit.isEmpty()) {
                        // stop the vehicle at divertable location and time (a stay task will be appended in the nextTask section)
                        var dummyPath = VrpPaths.calcAndCreatePath(currentLink, currentLink, divertableTime, router, travelTime);
                        ((OnlineDriveTaskTracker) currentTask.getTaskTracker()).divertPath(dummyPath);
                    } else {
                        // Divert the vehicle if destination has changed
                        assert stopsToVisit.peek() != null;
                        Id<Link> newDestination = stopsToVisit.peek().getLinkId();
                        Id<Link> oldDestination = ((DrtDriveTask) currentTask).getPath().getToLink().getId();
                        if (!oldDestination.toString().equals(newDestination.toString())) {
                            var newPath = VrpPaths.calcAndCreatePath(currentLink,
                                    network.getLinks().get(newDestination), divertableTime, router, travelTime);
                            ((OnlineDriveTaskTracker) currentTask.getTaskTracker()).divertPath(newPath);
                        }
                    }
                }

                // Stop task --> nothing need to be changed
            }
        }
    }

    private List<DrtRequest> readRequestsFromTimeBin(double now) {
        List<DrtRequest> newRequests = new ArrayList<>();
        for (DrtRequest prebookedRequest : prebookedRequests) {
            double latestDepartureTime = now + horizon;
            if (prebookedRequest.getEarliestStartTime() < latestDepartureTime) {
                newRequests.add(prebookedRequest);
            }
        }
        prebookedRequests.removeAll(newRequests);
        return newRequests;
    }

    private void initSchedules(Fleet fleet) {
        for (DvrpVehicle veh : fleet.getVehicles().values()) {
            if (veh.getServiceBeginTime() < serviceStartTime) {
                serviceStartTime = veh.getServiceBeginTime();
            }
            if (veh.getServiceEndTime() > serviceEndTime) {
                serviceEndTime = veh.getServiceEndTime();
            }
            veh.getSchedule().addTask(taskFactory.createStayTask(veh, veh.getServiceBeginTime(), veh.getServiceEndTime(), veh.getStartLink()));
        }
    }

    public record PreplannedRequest(PreplannedRequestKey key, double earliestStartTime,
                                    double latestStartTime, double latestArrivalTime) {
    }

    public record PreplannedRequestKey(Id<Person> passengerId, Id<Link> fromLinkId, Id<Link> toLinkId) {
        // TODO (long-term) consider using request ID?
    }

    public record PreplannedStop(PreplannedRequest preplannedRequest, boolean pickup) {
        private Id<Link> getLinkId() {
            return pickup ? preplannedRequest.key.fromLinkId : preplannedRequest.key.toLinkId;
        }
    }

    public record PreplannedSchedules(Map<PreplannedRequestKey, Id<DvrpVehicle>> preplannedRequestToVehicle,
                                      Map<Id<DvrpVehicle>, Queue<PreplannedStop>> vehicleToPreplannedStops,
                                      Map<PreplannedRequestKey, PreplannedRequest> unassignedRequests) {
    }

    static PreplannedRequest createFromRequest(DrtRequest request) {
        return new PreplannedRequest(new PreplannedRequestKey(request.getPassengerId(), request.getFromLink().getId(),
                request.getToLink().getId()), request.getEarliestStartTime(), request.getLatestStartTime(),
                request.getLatestArrivalTime());
    }

    public record OnlineVehicleInfo(DvrpVehicle vehicle, Link currentLink, double divertableTime) {

    }


}
