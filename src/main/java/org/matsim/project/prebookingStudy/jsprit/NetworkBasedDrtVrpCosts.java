package org.matsim.project.prebookingStudy.jsprit;

import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.cost.VehicleRoutingTransportCosts;
import com.graphhopper.jsprit.core.problem.driver.Driver;
import com.graphhopper.jsprit.core.problem.vehicle.Vehicle;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutility;
import org.matsim.core.router.speedy.SpeedyALTFactory;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.TravelTime;
import org.matsim.project.prebookingStudy.jsprit.utils.FreeSpeedTravelTimeWithRoundingUp;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * @author Chengqi Lu (luchengqi7)
 * <p>
 * This class is developed for DRT off-line optimization in junction with jsprit package. Note that in this first
 * implementation, we only accept MATSim link id (String) as the Location in the jsprit environment. In the future
 * Coordinate may also be accepted.
 */
public class NetworkBasedDrtVrpCosts implements VehicleRoutingTransportCosts {
    private final Network network;
    private final LeastCostPathCalculator router;
    private final boolean enableCache;
    private final int timeBinSize;
    private final Map<TravelCostKey, TravelCostValue> cacheMap;
    /**
     * In MATSim, each DRT stop has a fixed duration, regardless of the number of passengers boarding / alighting the
     * vehicle. In Jsprit, the service time is depending on the number of service to perform at the site. To solve this
     * problem, the drt stopping time is included in the travel time.
     *
     * When doing performance analysis for Jsprit solution, the drt stop duration should be subtracted from the total travel
     * time / on-board travel time. As in MATSim drop off time is not included in the trip.
     * */
    private final double drtStopDuration;

    public static class Builder {
        private final Network network;

        private boolean enableCache = false;
        private int cacheSizeLimit = 10000;
        private int timeBinSize = 86400;
        private TravelTime travelTime = new FreeSpeedTravelTimeWithRoundingUp();
        private double drtStopDuration = 60;

        public Builder(Network network) {
            this.network = network;
        }

        public Builder enableCache(boolean enableCache) {
            this.enableCache = enableCache;
            return this;
        }

        public Builder setCacheSizeLimit(int cacheSizeLimit) {
            this.cacheSizeLimit = cacheSizeLimit;
            return this;
        }

        /**
         * If variable travel time is used, the time bin should be set according to the time bin of the "TravelTime"
         * If constant travel time is used, set the value to the service end time (e.g. 86400 (24 hour), 108000 (30 hours))
         * Default value is preset to 86400 (24 hour)
         */
        public Builder timeBinSize(int timeBinSize) {
            this.timeBinSize = timeBinSize;
            return this;
        }

        public Builder travelTime(TravelTime travelTime) {
            this.travelTime = travelTime;
            return this;
        }

        public Builder drtStopDuration(double drtStopDuration) {
            this.drtStopDuration = drtStopDuration;
            return this;
        }

        public NetworkBasedDrtVrpCosts build() {
            return new NetworkBasedDrtVrpCosts(this);
        }
    }

    public double getDrtStopDuration() {
        return drtStopDuration;
    }

    private NetworkBasedDrtVrpCosts(Builder builder) {
        this.network = builder.network;
        this.enableCache = builder.enableCache;
        this.timeBinSize = builder.timeBinSize;
        this.cacheMap = new LinkedHashMap<>(builder.cacheSizeLimit);
        this.drtStopDuration = builder.drtStopDuration;
        this.router = new SpeedyALTFactory().createPathCalculator
                (network, new OnlyTimeDependentTravelDisutility(builder.travelTime), builder.travelTime);
    }

    private static class TravelCostKey {
        private final String from;
        private final String to;
        private final double timeBin;

        TravelCostKey(String from, String to, int timeBin) {
            this.from = from;
            this.to = to;
            this.timeBin = timeBin;
        }

        public String getFrom() {
            return from;
        }

        public String getTo() {
            return to;
        }

        public double getTimeBin() {
            return timeBin;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TravelCostKey that = (TravelCostKey) o;
            return Double.compare(that.timeBin, timeBin) == 0 && from.equals(that.from) && to.equals(that.to);
        }

        @Override
        public int hashCode() {
            return Objects.hash(from, to, timeBin);
        }
    }

    private static class TravelCostValue {
        private final double travelCost;
        private final double travelTime;
        private final double travelDistance;

        TravelCostValue(double travelCost, double travelTime, double travelDistance) {
            this.travelCost = travelCost;
            this.travelTime = travelTime;
            this.travelDistance = travelDistance;
        }

        public double getTravelTime() {
            return travelTime;
        }

        public double getTravelCost() {
            return travelCost;
        }

        public double getTravelDistance() {
            return travelDistance;
        }
    }

    @Override
    public double getTransportCost(Location from, Location to, double departureTime, Driver driver, Vehicle vehicle) {
        if (from.getId().equals(to.getId())) {
            return 0;
        }
        Link fromLink = network.getLinks().get(Id.createLinkId(from.getId()));
        Link toLink = network.getLinks().get(Id.createLinkId(to.getId()));
        if (enableCache) {
            int timeBin = (int) (departureTime / timeBinSize);
            TravelCostKey travelCostKey = new TravelCostKey(from.getId(), to.getId(), timeBin);
            TravelCostValue travelCostValue = cacheMap.get(travelCostKey);
            if (travelCostValue == null) {
                travelCostValue = calculateTravelCostValue(fromLink, toLink, departureTime);
            }
            return travelCostValue.getTravelCost();
        }
        return router.calcLeastCostPath(fromLink.getToNode(), toLink.getToNode(), departureTime, null, null).travelCost;
    }

    @Override
    public double getTransportTime(Location from, Location to, double departureTime, Driver driver, Vehicle vehicle) {
        if (from.getId().equals(to.getId())) {
            return drtStopDuration;
        }
        Link fromLink = network.getLinks().get(Id.createLinkId(from.getId()));
        Link toLink = network.getLinks().get(Id.createLinkId(to.getId()));
        if (enableCache) {
            int timeBin = (int) (departureTime / timeBinSize);
            TravelCostKey travelCostKey = new TravelCostKey(from.getId(), to.getId(), timeBin);
            TravelCostValue travelCostValue = cacheMap.get(travelCostKey);
            if (travelCostValue == null) {
                travelCostValue = calculateTravelCostValue(fromLink, toLink, departureTime);
            }
            return travelCostValue.getTravelTime() + drtStopDuration;
        }
        return router.calcLeastCostPath(fromLink.getToNode(), toLink.getToNode(), departureTime, null, null).travelTime + drtStopDuration;
    }

    @Override
    public double getDistance(Location from, Location to, double departureTime, Vehicle vehicle) {
        if (from.getId().equals(to.getId())) {
            return 0;
        }
        Link fromLink = network.getLinks().get(Id.createLinkId(from.getId()));
        Link toLink = network.getLinks().get(Id.createLinkId(to.getId()));
        int timeBin = (int) (departureTime / timeBinSize);
        if (enableCache) {
            TravelCostKey travelCostKey = new TravelCostKey(from.getId(), to.getId(), timeBin);
            TravelCostValue travelCostValue = cacheMap.get(travelCostKey);
            if (travelCostValue == null) {
                travelCostValue = calculateTravelCostValue(fromLink, toLink, departureTime);
            }
            return travelCostValue.getTravelDistance();
        }
        return router.
                calcLeastCostPath(fromLink.getToNode(), toLink.getToNode(), departureTime, null, null).
                links.stream().mapToDouble(Link::getLength).sum();
    }

    @Override
    public double getBackwardTransportCost(Location from, Location to, double arrivalTime, Driver driver, Vehicle vehicle) {
        return getTransportCost(from, to, arrivalTime, driver, vehicle);
    }

    @Override
    public double getBackwardTransportTime(Location from, Location to, double arrivalTime, Driver driver, Vehicle vehicle) {
        return getTransportTime(from, to, arrivalTime, driver, vehicle);
    }

    private TravelCostValue calculateTravelCostValue(Link fromLink, Link toLink, double departureTime) {
        LeastCostPathCalculator.Path path = router.calcLeastCostPath(fromLink.getToNode(), toLink.getToNode(), departureTime, null, null);
        int timeBin = (int) (departureTime / timeBinSize);
        TravelCostKey travelCostKey = new TravelCostKey(fromLink.getId().toString(), toLink.getId().toString(), timeBin);
        TravelCostValue travelCostValue = new TravelCostValue(path.travelCost, path.travelTime, path.links.stream().mapToDouble(Link::getLength).sum());
        cacheMap.put(travelCostKey, travelCostValue); // Store in the cache map
        return travelCostValue;
    }
}
