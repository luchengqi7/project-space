package org.matsim.project.prebookingStudy.roolingHorizon;

import com.google.common.base.Preconditions;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.contrib.drt.extension.preplanned.optimizer.PreplannedDrtOptimizer;
import org.matsim.contrib.drt.extension.preplanned.optimizer.WaitForStopTask;
import org.matsim.contrib.drt.optimizer.DrtOptimizer;
import org.matsim.contrib.drt.optimizer.VehicleEntry;
import org.matsim.contrib.drt.optimizer.Waypoint;
import org.matsim.contrib.drt.passenger.AcceptedDrtRequest;
import org.matsim.contrib.drt.passenger.DrtRequest;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.schedule.DrtDriveTask;
import org.matsim.contrib.drt.schedule.DrtTaskFactory;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.fleet.Fleet;
import org.matsim.contrib.dvrp.optimizer.Request;
import org.matsim.contrib.dvrp.passenger.PassengerRequestRejectedEvent;
import org.matsim.contrib.dvrp.passenger.PassengerRequestScheduledEvent;
import org.matsim.contrib.dvrp.path.VrpPathWithTravelData;
import org.matsim.contrib.dvrp.path.VrpPaths;
import org.matsim.contrib.dvrp.schedule.Schedule;
import org.matsim.contrib.dvrp.schedule.ScheduleTimingUpdater;
import org.matsim.contrib.dvrp.schedule.Tasks;
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

    private final List<DrtRequest> prebookedRequests = new ArrayList<>();

    private PreplannedDrtOptimizer.PreplannedSchedules preplannedSchedules;

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

        var preplannedRequest = PreplannedDrtOptimizer.PreplannedRequest.createFromRequest(drtRequest);
        var vehicleId = preplannedSchedules.preplannedRequestToVehicle.get(preplannedRequest);

        if (vehicleId == null) {
            Preconditions.checkState(preplannedSchedules.unassignedRequests.contains(preplannedRequest),
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
        var schedule = vehicle.getSchedule();

        //TODO we could even skip adding this dummy task
        if (schedule.getStatus() == Schedule.ScheduleStatus.PLANNED) {
            //just execute the initially inserted 0-duration wait task
            schedule.nextTask();
            return;
        }

        var currentTask = schedule.getCurrentTask();
        var currentLink = Tasks.getEndLink(currentTask);
        double currentTime = timer.getTimeOfDay();
        var nonVisitedPreplannedStops = preplannedSchedules.vehicleToPreplannedStops.get(vehicle.getId());
        var nextStop = nonVisitedPreplannedStops.peek();

        if (nextStop == null) {
            // no more preplanned stops, add STAY only if still operating
            if (currentTime < vehicle.getServiceEndTime()) {
                // fill the time gap with STAY
                schedule.addTask(
                        taskFactory.createStayTask(vehicle, currentTime, vehicle.getServiceEndTime(), currentLink));
            } else if (!STAY.isBaseTypeOf(currentTask)) {
                // always end with STAY even if delayed
                schedule.addTask(taskFactory.createStayTask(vehicle, currentTime, currentTime, currentLink));
            }
            //if none of the above, this is the end of schedule
        } else if (!nextStop.getLinkId().equals(currentLink.getId())) {
            var nextLink = network.getLinks().get(nextStop.getLinkId());
            VrpPathWithTravelData path = VrpPaths.calcAndCreatePath(currentLink, nextLink, currentTime, router,
                    travelTime);
            schedule.addTask(taskFactory.createDriveTask(vehicle, path, DrtDriveTask.TYPE));
        } else if (nextStop.preplannedRequest.earliestStartTime >= timer.getTimeOfDay()) {
            // we need to wait 1 time step to make sure we have already received the request submission event
            // otherwise we may not be able to get the request and insert it to the stop task
            // TODO currently assuming the mobsim time step is 1 s
            schedule.addTask(
                    new WaitForStopTask(currentTime, nextStop.preplannedRequest.earliestStartTime + 1, currentLink));
        } else {
            nonVisitedPreplannedStops.poll();//remove this stop from queue

            var stopTask = taskFactory.createStopTask(vehicle, currentTime, currentTime + stopDuration, currentLink);
            if (nextStop.pickup) {
                var request = Preconditions.checkNotNull(openRequests.get(nextStop.preplannedRequest.getPassengerId()),
                        "Request (%s) has not been yet submitted", nextStop.preplannedRequest);
                stopTask.addPickupRequest(AcceptedDrtRequest.createFromOriginalRequest(request));
            } else {
                var request = Preconditions.checkNotNull(openRequests.remove(nextStop.preplannedRequest.getPassengerId()),
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
        if (now % interval == 0) {
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

                requestsOnboard.get(vehicleEntry).addAll(passengersOnboard);
                acceptedWaitingRequests.addAll(requestsToBePickedUp);
            }

            // Update the preplanned schedule
            preplannedSchedules = solver.calculate((Set<VehicleEntry>) vehicleEntries.values(), newRequests,
                    requestsOnboard, acceptedWaitingRequests, updatedLatestPickUpTimeMap, updatedLatestDropOffTimeMap);
        }
    }

    private List<DrtRequest> readRequestsFromTimeBin(double now) {
        List<DrtRequest> newRequests = new ArrayList<>();
        for (DrtRequest prebookedRequest : prebookedRequests) {
            if (prebookedRequest.getEarliestStartTime() <= now + horizon) {
                newRequests.add(prebookedRequest);
            }
        }
        prebookedRequests.removeAll(newRequests);
        return newRequests;
    }
}
