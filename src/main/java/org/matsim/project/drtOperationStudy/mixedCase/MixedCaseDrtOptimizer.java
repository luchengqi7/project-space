package org.matsim.project.drtOperationStudy.mixedCase;

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
import scala.Int;

import java.util.*;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;

import static org.matsim.contrib.drt.schedule.DrtTaskBaseType.STAY;

public class MixedCaseDrtOptimizer implements DrtOptimizer {
    private final Logger log = LogManager.getLogger(MixedCaseDrtOptimizer.class);
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
    private final PrebookedRequestsSolverJsprit solver;
    private final SimpleOnlineInserter inserter;

    private final double horizon;
    private final double interval; // Must be smaller than or equal to the horizon
    private double serviceStartTime = Double.MAX_VALUE;  // Start time of the whole DRT service (will be set to the earliest starting time of all the fleet)
    private double serviceEndTime = 0; // End time of the whole DRT service (will be set to the latest ending time of all the fleet)

    private LinkToLinkTravelTimeMatrix travelTimeMatrix;

    private final List<DrtRequest> prebookedRequests = new ArrayList<>();

    private FleetSchedules fleetSchedules;
    Map<Id<DvrpVehicle>, OnlineVehicleInfo> realTimeVehicleInfoMap = new HashMap<>();

    public MixedCaseDrtOptimizer(Network network, TravelTime travelTime, MobsimTimer timer, DrtTaskFactory taskFactory,
                                 EventsManager eventsManager, ScheduleTimingUpdater scheduleTimingUpdater,
                                 TravelDisutility travelDisutility, double stopDuration, String mode, DrtConfigGroup drtCfg,
                                 Fleet fleet, ForkJoinPool forkJoinPool, VehicleEntry.EntryFactory vehicleEntryFactory,
                                 PrebookedRequestsSolverJsprit solver, SimpleOnlineInserter inserter, Population plans,
                                 double horizon, double interval, Population prebookedTrips) {
        this.network = network;
        this.travelTime = travelTime;
        this.timer = timer;
        this.taskFactory = taskFactory;
        this.eventsManager = eventsManager;
        this.scheduleTimingUpdater = scheduleTimingUpdater;
        this.router = new SpeedyALTFactory().createPathCalculator(network, travelDisutility, travelTime);
        this.stopDuration = stopDuration;
        this.mode = mode;
        this.drtCfg = drtCfg;
        this.fleet = fleet;
        this.forkJoinPool = forkJoinPool;
        this.vehicleEntryFactory = vehicleEntryFactory;
        this.solver = solver;
        this.inserter = inserter;
        this.horizon = horizon;
        this.interval = interval;

        initDrtSchedules();
        readPrebookedRequests(plans, prebookedTrips);
        assert interval <= horizon : "Interval of optimization must be smaller than or equal to the horizon length!";

    }

    @Override
    public void requestSubmitted(Request request) {
        if (timer.getTimeOfDay() == 0) {
            throw new RuntimeException("Currently, we cannot deal with request submitted at t = 0. Please remove such requests!");
        }

        DrtRequest drtRequest = (DrtRequest) request;
        Id<Person> passengerId = drtRequest.getPassengerId();
        openRequests.put(((DrtRequest) request).getPassengerId(), drtRequest);

        if (fleetSchedules.requestIdToVehicleMap.containsKey(passengerId)
                || fleetSchedules.rejectedRequests.contains(passengerId)) {
            // This is a pre-booked request
            Id<DvrpVehicle> vehicleId = fleetSchedules.requestIdToVehicleMap.get(passengerId);
            if (vehicleId == null) {
                Preconditions.checkState(fleetSchedules.rejectedRequests.contains(passengerId),
                        "Pre-planned request (%s) not assigned to any vehicle and not marked as unassigned.",
                        passengerId);
                eventsManager.processEvent(new PassengerRequestRejectedEvent(timer.getTimeOfDay(), mode, request.getId(),
                        passengerId, "Marked as unassigned"));
                return;
            }

            eventsManager.processEvent(
                    new PassengerRequestScheduledEvent(timer.getTimeOfDay(), drtRequest.getMode(), drtRequest.getId(),
                            drtRequest.getPassengerId(), vehicleId, Double.NaN, Double.NaN));
            // Currently, we don't provide the expected pickup / drop off time. Maybe update this in the future.

        } else {
            // This is a spontaneous request
            Id<DvrpVehicle> selectedVehicleId = inserter.insert(drtRequest, fleetSchedules.vehicleToTimetableMap, realTimeVehicleInfoMap, travelTimeMatrix);
            if (selectedVehicleId != null) {
                eventsManager.processEvent(
                        new PassengerRequestScheduledEvent(timer.getTimeOfDay(), drtRequest.getMode(), drtRequest.getId(),
                                drtRequest.getPassengerId(), selectedVehicleId, Double.NaN, Double.NaN)); //TODO add estimated pickup / arrival time
            } else {
                eventsManager.processEvent(new PassengerRequestRejectedEvent(timer.getTimeOfDay(), mode, request.getId(),
                        passengerId, "No feasible insertion. The spontaneous request is rejected"));
            }
        }
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

        var stopsToVisit = fleetSchedules.vehicleToTimetableMap.get(vehicle.getId());

        if (stopsToVisit.isEmpty()) {
            // no preplanned stops for the vehicle within current horizon
            if (currentTime < vehicle.getServiceEndTime()) {
                // fill the time gap with STAY
                schedule.addTask(taskFactory.createStayTask(vehicle, currentTime, vehicle.getServiceEndTime(), currentLink));
            } else if (!STAY.isBaseTypeOf(currentTask)) {
                // we need to end the schedule with STAY task even if it is delayed
                schedule.addTask(taskFactory.createStayTask(vehicle, currentTime, currentTime, currentLink));
            }
        } else {
            var nextStop = stopsToVisit.get(0);
            if (!nextStop.getLinkId().equals(currentLink.getId())) {
                // Next stop is at another location? --> Add a drive task
                var nextLink = network.getLinks().get(nextStop.getLinkId());
                VrpPathWithTravelData path = VrpPaths.calcAndCreatePath(currentLink, nextLink, currentTime, router,
                        travelTime);
                schedule.addTask(taskFactory.createDriveTask(vehicle, path, DrtDriveTask.TYPE));
            } else if (nextStop.getRequest().earliestStartTime >= timer.getTimeOfDay()) {
                // We are at the stop location. But we are too early. --> Add a wait for stop task
                // Currently assuming the mobsim time step is 1 s
                schedule.addTask(new WaitForStopTask(currentTime,
                        nextStop.getRequest().earliestStartTime + 1, currentLink));
            } else {
                // We are ready for the stop task! --> Add stop task to the schedule
                var stopTask = taskFactory.createStopTask(vehicle, currentTime, currentTime + stopDuration, currentLink);
                if (nextStop.stopType == TimetableEntry.StopType.PICKUP) {
                    var request = Preconditions.checkNotNull(openRequests.get(nextStop.getRequest().passengerId()),
                            "Request (%s) has not been yet submitted", nextStop.getRequest());
                    stopTask.addPickupRequest(AcceptedDrtRequest.createFromOriginalRequest(request));
                } else {
                    var request = Preconditions.checkNotNull(openRequests.remove(nextStop.getRequest().passengerId()),
                            "Request (%s) has not been yet submitted", nextStop.getRequest());
                    stopTask.addDropoffRequest(AcceptedDrtRequest.createFromOriginalRequest(request));
                }
                schedule.addTask(stopTask);
                stopsToVisit.remove(0); //remove the first entry in the stops to visit list
            }
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
            List<GeneralRequest> newRequests = readRequestsFromTimeBin(now);

            // Calculate the new preplanned schedule
            double endTime = now + horizon;
            log.info("Calculating the plan for t =" + now + " to t = " + endTime);
            log.info("There are " + newRequests.size() + " new request within this horizon");
            fleetSchedules = solver.calculate(fleetSchedules, realTimeVehicleInfoMap, newRequests, now);
            travelTimeMatrix = solver.getTravelTimeMatrix();  // Update the link-to-link travel time matrix

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
                    var stopsToVisit = fleetSchedules.vehicleToTimetableMap.get(vehicle.getId());
                    if (stopsToVisit.isEmpty()) {
                        // stop the vehicle at divertable location and time (a stay task will be appended in the nextTask section)
                        var dummyPath = VrpPaths.calcAndCreatePath(currentLink, currentLink, divertableTime, router, travelTime);
                        ((OnlineDriveTaskTracker) currentTask.getTaskTracker()).divertPath(dummyPath);
                    } else {
                        // Divert the vehicle if destination has changed
                        assert stopsToVisit.get(0) != null;
                        Id<Link> newDestination = stopsToVisit.get(0).getLinkId();
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

    // Records and inner classes
    record FleetSchedules(
            Map<Id<DvrpVehicle>, List<TimetableEntry>> vehicleToTimetableMap,
            Map<Id<Person>, Id<DvrpVehicle>> requestIdToVehicleMap,
            List<Id<Person>> rejectedRequests) {
    }

    record GeneralRequest(Id<Person> passengerId, Id<Link> fromLinkId, Id<Link> toLinkId,
                          double earliestStartTime, double latestStartTime, double latestArrivalTime) {
    }

    record OnlineVehicleInfo(DvrpVehicle vehicle, Link currentLink, double divertableTime) {
    }

    // Static functions
    static GeneralRequest createFromDrtRequest(DrtRequest drtRequest) {
        return new GeneralRequest(drtRequest.getPassengerId(), drtRequest.getFromLink().getId(),
                drtRequest.getToLink().getId(), drtRequest.getEarliestStartTime(), drtRequest.getLatestStartTime(),
                drtRequest.getLatestArrivalTime());
    }

    // Private functions
    private void initDrtSchedules() {
        for (DvrpVehicle veh : fleet.getVehicles().values()) {
            // Identify the earliest starting time and/or latest ending time of the fleet
            if (veh.getServiceBeginTime() < serviceStartTime) {
                serviceStartTime = veh.getServiceBeginTime();
            }
            if (veh.getServiceEndTime() > serviceEndTime) {
                serviceEndTime = veh.getServiceEndTime();
            }

            veh.getSchedule().addTask(taskFactory.createStayTask(veh, veh.getServiceBeginTime(), veh.getServiceEndTime(), veh.getStartLink()));
        }
    }

    private void readPrebookedRequests(Population plans, Population prebookedTrips) {
        int counter = 0;
        for (Person person : plans.getPersons().values()) {
            if (!prebookedTrips.getPersons().containsKey(person.getId())) {
                continue;
            }
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
        log.info("There are " + counter + " pre-booked trips");
    }

    private List<GeneralRequest> readRequestsFromTimeBin(double now) {
        List<DrtRequest> newRequests = new ArrayList<>();
        for (DrtRequest prebookedRequest : prebookedRequests) {
            double latestDepartureTime = now + horizon;
            if (prebookedRequest.getEarliestStartTime() < latestDepartureTime) {
                newRequests.add(prebookedRequest);
            }
        }
        prebookedRequests.removeAll(newRequests);
        return newRequests.stream().map(MixedCaseDrtOptimizer::createFromDrtRequest).collect(Collectors.toList());
    }
}
