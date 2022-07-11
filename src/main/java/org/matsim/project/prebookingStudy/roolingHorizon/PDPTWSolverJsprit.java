package org.matsim.project.prebookingStudy.roolingHorizon;

import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.job.Shipment;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TimeWindow;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleImpl;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleTypeImpl;
import com.graphhopper.jsprit.core.util.Coordinate;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.IdMap;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.drt.extension.preplanned.optimizer.PreplannedDrtOptimizer;
import org.matsim.contrib.drt.optimizer.VehicleEntry;
import org.matsim.contrib.drt.passenger.AcceptedDrtRequest;
import org.matsim.contrib.drt.passenger.DrtRequest;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.dvrp.optimizer.Request;
import org.matsim.project.prebookingStudy.jsprit.MatrixBasedVrpCosts;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class PDPTWSolverJsprit {
    public static class Options {
        public final int maxIterations;
        public final boolean multiThread;

        public Options(int maxIterations, boolean multiThread) {
            this.maxIterations = maxIterations;
            this.multiThread = multiThread;
        }
    }

    private final Options options;
    private final DrtConfigGroup drtCfg;
    private final Network network;
    private final Map<Id<Link>, Location> locationByLinkId = new IdMap<>(Link.class);

    public PDPTWSolverJsprit(DrtConfigGroup drtCfg, Network network, Options options) {
        this.drtCfg = drtCfg;
        this.network = network;
        this.options = options;
    }

    public PreplannedDrtOptimizer.PreplannedSchedules calculate(Set<VehicleEntry> vehicleEntries,
                                                                List<DrtRequest> newRequests,
                                                                Map<VehicleEntry, List<AcceptedDrtRequest>> requestsOnboard,
                                                                List<AcceptedDrtRequest> acceptedWaitingRequests,
                                                                Map<Id<Request>, Double> updatedLatestPickUpTimeMap,
                                                                Map<Id<Request>, Double> updatedLatestDropOffTimeMap) {
        // Create PDPTW problem
        var vrpBuilder = new VehicleRoutingProblem.Builder();
        // 1. Vehicle
        for (VehicleEntry vehicleEntry : vehicleEntries) {
            Link currentLink = vehicleEntry.start.link; //TODO is this correct? Or maybe the divertable location for driving vehicles (i.e., the next link)
            double divertableTime = vehicleEntry.start.time; // TODO is this correct?
            int capacity = vehicleEntry.vehicle.getCapacity();
            double serviceEndTime = vehicleEntry.vehicle.getServiceEndTime();
            var vehicleType = VehicleTypeImpl.Builder.newInstance(drtCfg.getMode() + "-vehicle")
                    .addCapacityDimension(0, capacity)
                    .build();

            var vehicleBuilder = VehicleImpl.Builder.newInstance(vehicleEntry.vehicle.getId() + "");
            vehicleBuilder.setEarliestStart(divertableTime);
            vehicleBuilder.setLatestArrival(serviceEndTime);
            vehicleBuilder.setStartLocation(collectLocationIfAbsent(currentLink));
            vehicleBuilder.setType(vehicleType);
            vehicleBuilder.addSkill(vehicleEntry.vehicle.getId().toString());
            // Vehicle skills can be used to make sure the request already onboard will be matched to the same vehicle

            vrpBuilder.addVehicle(vehicleBuilder.build());
        }

        // 2. Request
        var preplannedRequestByShipmentId = new HashMap<String, PreplannedDrtOptimizer.PreplannedRequest>();

        // 2.0 collect requests locations and compute the matrix
        newRequests.forEach(drtRequest -> collectLocationIfAbsent(drtRequest.getFromLink()));
        newRequests.forEach(drtRequest -> collectLocationIfAbsent(drtRequest.getToLink()));
        acceptedWaitingRequests.forEach(acceptedDrtRequest -> collectLocationIfAbsent(acceptedDrtRequest.getFromLink()));
        acceptedWaitingRequests.forEach(acceptedDrtRequest -> collectLocationIfAbsent(acceptedDrtRequest.getToLink()));
        for (List<AcceptedDrtRequest> acceptedDrtRequests : requestsOnboard.values()) {
            acceptedDrtRequests.forEach(acceptedDrtRequest -> collectLocationIfAbsent(acceptedDrtRequest.getToLink()));
        }

        var vrpCosts = MatrixBasedVrpCosts.calculateVrpCosts(network, locationByLinkId);
        vrpBuilder.setRoutingCost(vrpCosts);

        // 2.1 Passengers onboard
        for (VehicleEntry vehicleEntry : requestsOnboard.keySet()) {
            Link startLink = vehicleEntry.start.link;
            double time = vehicleEntry.start.time;
            String skill = vehicleEntry.vehicle.getId().toString(); // We use skill to lock the vehicle and request already onboard
            for (AcceptedDrtRequest requestOnboard : requestsOnboard.get(vehicleEntry)) {
                double latestArrivalTime = requestOnboard.getLatestArrivalTime();
                if (updatedLatestDropOffTimeMap.containsKey(requestOnboard.getId())) {
                    latestArrivalTime = updatedLatestDropOffTimeMap.get(requestOnboard.getId());
                }
                var shipmentId = requestOnboard.getId() + "_dummy_" + time;
                var shipment = Shipment.Builder.newInstance(shipmentId).
                        setPickupLocation(collectLocationIfAbsent(startLink)).
                        setDeliveryLocation(collectLocationIfAbsent(requestOnboard.getToLink())).
                        setPickupTimeWindow(new TimeWindow(time, time)).
                        setPickupServiceTime(0).
                        setDeliveryServiceTime(drtCfg.getStopDuration()).
                        setDeliveryTimeWindow(new TimeWindow(time, latestArrivalTime)).
                        addSizeDimension(0, 1).
                        addRequiredSkill(skill).
                        setPriority(1).  // 1 --> top priority. 10 --> the lowest priority
                        build();
                vrpBuilder.addJob(shipment);
            }
        }

        // 2.2 Already accepted requests (not yet picked up)


        // 2.3 New requests



        return null;
    }

    private Location collectLocationIfAbsent(Link link) {
        return locationByLinkId.computeIfAbsent(link.getId(), linkId -> Location.Builder.newInstance()
                .setId(link.getId() + "")
                .setIndex(locationByLinkId.size())
                .setCoordinate(Coordinate.newInstance(link.getCoord().getX(), link.getCoord().getY()))
                .build());
    }

}
