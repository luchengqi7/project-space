package org.matsim.project.prebookingStudy.rollingHorizon;

import com.google.common.base.Preconditions;
import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.contrib.drt.extension.preplanned.optimizer.WaitForStopTask;
import org.matsim.contrib.drt.optimizer.DrtOptimizer;
import org.matsim.contrib.drt.optimizer.VehicleEntry;
import org.matsim.contrib.drt.optimizer.Waypoint;
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
    private static final Logger log = Logger.getLogger(RollingHorizonDrtOptimizer.class);
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

    private final double horizon = 1800; // TODO make it configurable
    private final double interval = 1800; // Smaller than or equal to the horizon TODO make it configurable
    private double serviceStartTime = Double.MAX_VALUE;
    private double serviceEndTime = 0;


    private final List<DrtRequest> prebookedRequests = new ArrayList<>();

    private PreplannedSchedules preplannedSchedules;

    public RollingHorizonDrtOptimizer(DrtConfigGroup drtCfg, Network network, TravelTime travelTime,
                                      TravelDisutility travelDisutility, MobsimTimer timer, DrtTaskFactory taskFactory,
                                      EventsManager eventsManager, Fleet fleet, ScheduleTimingUpdater scheduleTimingUpdater,
                                      ForkJoinPool forkJoinPool, VehicleEntry.EntryFactory vehicleEntryFactory,
                                      PDPTWSolverJsprit pdptwSolverJsprit, Population plans) {
        this.network = network;
        this.travelTime = travelTime;
        this.timer = timer;
        this.taskFactory = taskFactory;
        this.eventsManager = eventsManager;
        this.scheduleTimingUpdater = scheduleTimingUpdater;
        this.solver = pdptwSolverJsprit;
        this.router = new SpeedyALTFactory().createPathCalculator(network, travelDisutility, travelTime);
        this.stopDuration = drtCfg.getStopDuration();
        this.fleet = fleet;
        this.forkJoinPool = forkJoinPool;
        this.vehicleEntryFactory = vehicleEntryFactory;
        this.drtCfg = drtCfg;
        this.mode = drtCfg.getMode();

        readPrebookedRequests(plans);
        initSchedules(fleet);
    }

    private void readPrebookedRequests(Population plans) {
        for (Person person : plans.getPersons().values()) {
            int counter = 0;
            for (var leg : TripStructureUtils.getLegs(person.getSelectedPlan())) {
                if (!leg.getMode().equals(mode)) {
                    continue;
                }
                var startLink = network.getLinks().get(leg.getRoute().getStartLinkId()); // TODO does this work? Where is the walking to link calculated
                var endLink = network.getLinks().get(leg.getRoute().getEndLinkId());
                double earliestPickupTime = leg.getDepartureTime().seconds();
                double latestPickupTime = earliestPickupTime + drtCfg.getMaxWaitTime();
                double estimatedDirectTravelTime = VrpPaths.calcAndCreatePath(startLink, endLink, earliestPickupTime, router, travelTime).getTravelTime();
                double latestArrivalTime = earliestPickupTime + drtCfg.getMaxTravelTimeAlpha() * estimatedDirectTravelTime + drtCfg.getMaxTravelTimeBeta();
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
            Preconditions.checkState(preplannedSchedules.unassignedRequests.containsValue(preplannedRequest),
                    "Pre-planned request (%s) not assigned to any vehicle and not marked as unassigned.",
                    preplannedRequest);
            eventsManager.processEvent(new PassengerRequestRejectedEvent(timer.getTimeOfDay(), mode, request.getId(),
                    drtRequest.getPassengerId(), "Marked as unassigned"));
            return;
        }

        var preplannedStops = preplannedSchedules.vehicleToPreplannedStops.get(vehicleId);

        Preconditions.checkState(!preplannedStops.isEmpty(),
                "Expected to contain at least preplanned stops for request (%s)", request.getId());

        //TODO in the current implementation we do not know the scheduled pickup and dropoff times
        eventsManager.processEvent(
                new PassengerRequestScheduledEvent(timer.getTimeOfDay(), drtRequest.getMode(), drtRequest.getId(),
                        drtRequest.getPassengerId(), vehicleId, Double.NaN, Double.NaN));

    }

    @Override
    public void nextTask(DvrpVehicle vehicle) {
        scheduleTimingUpdater.updateBeforeNextTask(vehicle);
        vehicle.getSchedule().nextTask();

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
            Map<VehicleEntry, List<AcceptedDrtRequest>> requestsOnboard = new HashMap<>();
            List<AcceptedDrtRequest> acceptedWaitingRequests = new ArrayList<>();
            Map<Id<Request>, Double> updatedLatestPickUpTimeMap = new HashMap<>();
            Map<Id<Request>, Double> updatedLatestDropOffTimeMap = new HashMap<>();
            var vehicleEntries = forkJoinPool.submit(() -> fleet.getVehicles()
                    .values()
                    .parallelStream()
                    .map(v -> vehicleEntryFactory.create(v, now))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toMap(e -> e.vehicle.getId(), e -> e))).join();
            List<DrtRequest> newRequests = readRequestsFromTimeBin(now);

            //TODO delete begin
            System.err.println("At time = " + now + ", there are " + newRequests.size() + " new requests");
            for (DrtRequest request : newRequests) {
                System.err.println("Request ID: " + request.getId().toString() + " to be submitted at "
                        + request.getSubmissionTime() + " earliest departure time = " + request.getEarliestStartTime());
            }
            // TODO delete end

            // Begin reading data
            for (Id<DvrpVehicle> vehicleId : vehicleEntries.keySet()) {
                VehicleEntry vehicleEntry = vehicleEntries.get(vehicleId);

                // Get persons onboard the vehicle and accepted waiting requests (accepted but not yet picked up)
                List<AcceptedDrtRequest> passengersOnboard = new ArrayList<>();
                List<AcceptedDrtRequest> requestsToBePickedUp = new ArrayList<>();

                for (Waypoint.Stop stop : vehicleEntry.stops) {
                    double updatedTime = stop.task.getBeginTime(); //TODO double check: is the begin time of the task updated by scheduleTimingUpdater?
                    for (AcceptedDrtRequest acceptedDrtRequest : stop.task.getDropoffRequests().values()) {
                        passengersOnboard.add(acceptedDrtRequest); // Intermediate result (to be processed with remove all operation below)
                        double latestArrivalTime = acceptedDrtRequest.getLatestArrivalTime();
                        if (latestArrivalTime < updatedTime) {
                            updatedLatestDropOffTimeMap.put(acceptedDrtRequest.getId(), updatedTime);
                        }
                    }

                    for (AcceptedDrtRequest acceptedDrtRequest : stop.task.getPickupRequests().values()) {
                        requestsToBePickedUp.add(acceptedDrtRequest);
                        double latestPickUpTime = acceptedDrtRequest.getLatestStartTime();
                        if (latestPickUpTime < updatedTime) {
                            updatedLatestPickUpTimeMap.put(acceptedDrtRequest.getId(), updatedTime);
                        }
                    }
                }
                passengersOnboard.removeAll(requestsToBePickedUp);

                requestsOnboard.computeIfAbsent(vehicleEntry, p -> new ArrayList<>()).addAll(passengersOnboard);
                acceptedWaitingRequests.addAll(requestsToBePickedUp);
            }

            // Analyze vehicle currnet information
            List<OnlineVehicleInfo> onlineVehicleInfos = new ArrayList<>();
            for (VehicleEntry vehicleEntry : vehicleEntries.values()) {
                Schedule schedule = vehicleEntry.vehicle.getSchedule();
                Task currentTask = schedule.getCurrentTask();

                Link currentLink = null;
                double divertableTime = Double.NaN;

                if (currentTask instanceof DrtStayTask) {
                    currentLink = ((DrtStayTask) currentTask).getLink();
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

                assert currentLink != null;
                assert !Double.isNaN(divertableTime);
                OnlineVehicleInfo onlineVehicleInfo = new OnlineVehicleInfo(vehicleEntry.vehicle, currentLink, divertableTime);
                onlineVehicleInfos.add(onlineVehicleInfo);
            }

            // Calculate the new preplanned schedule
            double endTime = now + horizon;
            log.info("Calculating the plan for t =" + now + " to t = " + endTime);
            log.info("There are " + newRequests.size() + " new request within this horizon");
            preplannedSchedules = solver.calculate(onlineVehicleInfos, newRequests,
                    requestsOnboard, acceptedWaitingRequests, updatedLatestPickUpTimeMap, updatedLatestDropOffTimeMap);

            // Update vehicles schedules
            for (OnlineVehicleInfo onlineVehicleInfo : onlineVehicleInfos) {
                DvrpVehicle vehicle = onlineVehicleInfo.vehicle;
                Schedule schedule = vehicle.getSchedule();
                Task currentTask = schedule.getCurrentTask();
                Link currentLink = onlineVehicleInfo.currentLink;
                double divertableTime = onlineVehicleInfo.divertableTime;

                if (!(currentTask instanceof DrtStayTask)) {
                    // Clear the old schedule
                    int currentTaskIdx = currentTask.getTaskIdx();
                    int totalNumTasks = schedule.getTaskCount();
                    int tasksToRemove = totalNumTasks - currentTaskIdx - 1;
                    for (int i = 0; i < tasksToRemove; i++) {
                        schedule.removeLastTask();
                    }
                } else {
                    currentTask.setEndTime(now); // end current stay task
                }

                // Add new tasks based on preplanned schedule
                Queue<PreplannedStop> stopsToVisit = preplannedSchedules.vehicleToPreplannedStops.get(vehicle.getId());
                boolean finished = false;
                while (!finished) {
                    PreplannedStop nextStopToVisit = stopsToVisit.poll();
                    if (nextStopToVisit != null) {
                        // Add Drive task or Stop task
                        if (!nextStopToVisit.getLinkId().equals(currentLink.getId())) {
                            // Add a drive task
                            var nextLink = network.getLinks().get(nextStopToVisit.getLinkId());
                            VrpPathWithTravelData path =
                                    VrpPaths.calcAndCreatePath(currentLink, nextLink, divertableTime, router, travelTime);
                            schedule.addTask(taskFactory.createDriveTask(vehicle, path, DrtDriveTask.TYPE));

                            // "move" the vehicle to the new location and update the time
                            currentLink = path.getToLink();
                            divertableTime = path.getArrivalTime();

                        } else if (nextStopToVisit.preplannedRequest.earliestStartTime >= divertableTime) {
                            // Add a wait task
                            schedule.addTask(new WaitForStopTask(divertableTime, nextStopToVisit.preplannedRequest.earliestStartTime, currentLink));
                            divertableTime = nextStopToVisit.preplannedRequest.earliestStartTime;
                        } else {
                            // Add stop task
                            var stopTask = taskFactory.createStopTask(vehicle, divertableTime, divertableTime + stopDuration, currentLink);
                            // TODO the request should not have been submitted by now
                            if (nextStopToVisit.pickup) {
                                var request = Preconditions.checkNotNull(openRequests.get(nextStopToVisit.preplannedRequest.key().passengerId()),
                                        "Request (%s) has not been yet submitted", nextStopToVisit.preplannedRequest);
                                stopTask.addPickupRequest(AcceptedDrtRequest.createFromOriginalRequest(request));
                            } else {
                                var request = Preconditions.checkNotNull(openRequests.remove(nextStopToVisit.preplannedRequest.key().passengerId()),
                                        "Request (%s) has not been yet submitted", nextStopToVisit.preplannedRequest);
                                stopTask.addDropoffRequest(AcceptedDrtRequest.createFromOriginalRequest(request));
                            }
                            schedule.addTask(stopTask);
                            divertableTime = divertableTime + stopDuration;
                        }
                    } else {
                        // Add a stay task
                        if (divertableTime < vehicle.getServiceEndTime()) {
                            // fill the time gap with STAY
                            schedule.addTask(
                                    taskFactory.createStayTask(vehicle, divertableTime, vehicle.getServiceEndTime(), currentLink));
                        } else if (!STAY.isBaseTypeOf(currentTask)) {
                            // always end with STAY even if delayed
                            schedule.addTask(
                                    taskFactory.createStayTask(vehicle, vehicle.getServiceEndTime(), vehicle.getServiceEndTime(), currentLink));
                        }
                        finished = true;
                    }
                }
            }
        }
    }

    private List<DrtRequest> readRequestsFromTimeBin(double now) {
        List<DrtRequest> newRequests = new ArrayList<>();
        for (DrtRequest prebookedRequest : prebookedRequests) {
            double latestDepartureTime = now + horizon;
            if (prebookedRequest.getEarliestStartTime() <= latestDepartureTime) {
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
        // TODO (long-term) consider using request ID
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
