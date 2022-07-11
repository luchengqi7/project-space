package org.matsim.project.prebookingStudy.roolingHorizon;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Population;
import org.matsim.contrib.drt.optimizer.DrtOptimizer;
import org.matsim.contrib.drt.optimizer.VehicleEntry;
import org.matsim.contrib.drt.optimizer.Waypoint;
import org.matsim.contrib.drt.passenger.AcceptedDrtRequest;
import org.matsim.contrib.drt.passenger.DrtRequest;
import org.matsim.contrib.drt.scheduler.DrtScheduleInquiry;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.fleet.Fleet;
import org.matsim.contrib.dvrp.optimizer.Request;
import org.matsim.contrib.dvrp.schedule.ScheduleTimingUpdater;
import org.matsim.core.mobsim.framework.events.MobsimBeforeSimStepEvent;

import java.util.*;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;


public class RollingHorizonDrtOptimization implements DrtOptimizer {
    private final Fleet fleet;
    private final DrtScheduleInquiry scheduleInquiry;
    private final ScheduleTimingUpdater scheduleTimingUpdater;
    private final ForkJoinPool forkJoinPool;
    private final VehicleEntry.EntryFactory vehicleEntryFactory;

    private final PDPTWSolverJsprit solver; // TODO make an interface for this

    private final double horizon = 1800; // TODO make it configurable
    private final double interval = 1800; // Smaller than or equal to the horizon TODO make it configurable


    private List<DrtRequest> prebookedRequests = new ArrayList<>();

    public RollingHorizonDrtOptimization(Fleet fleet, DrtScheduleInquiry scheduleInquiry,
                                         ScheduleTimingUpdater scheduleTimingUpdater, ForkJoinPool forkJoinPool,
                                         VehicleEntry.EntryFactory vehicleEntryFactory,
                                         PDPTWSolverJsprit pdptwSolverJsprit, Population plans) {
        this.fleet = fleet;
        this.scheduleInquiry = scheduleInquiry;
        this.scheduleTimingUpdater = scheduleTimingUpdater;
        this.forkJoinPool = forkJoinPool;
        this.vehicleEntryFactory = vehicleEntryFactory;
        this.solver = pdptwSolverJsprit;
        readPrebookedRequests(plans);
    }

    private void readPrebookedRequests(Population plans) {
        //TODO
    }

    @Override
    public void requestSubmitted(Request request) {
        // TODO later we also accept spontaneous requests
    }

    @Override
    public void nextTask(DvrpVehicle dvrpVehicle) {
        scheduleTimingUpdater.updateBeforeNextTask(dvrpVehicle);
        dvrpVehicle.getSchedule().nextTask();
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
                    double updatedTime = stop.task.getBeginTime(); //TODO double check: is the begin time of the task should be updated by scheduleTimingUpdater?
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

            // Pass the information to the solver and get the solution


            // Process the solution and update vehicle schedule based on the solution

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
