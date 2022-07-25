package org.matsim.project.congestionAwareDrt;

import org.apache.commons.lang3.mutable.MutableInt;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.LinkEnterEvent;
import org.matsim.api.core.v01.events.VehicleEntersTrafficEvent;
import org.matsim.api.core.v01.events.handler.LinkEnterEventHandler;
import org.matsim.api.core.v01.events.handler.VehicleEntersTrafficEventHandler;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.dvrp.trafficmonitoring.QSimFreeSpeedTravelTime;
import org.matsim.core.mobsim.framework.events.MobsimBeforeSimStepEvent;
import org.matsim.core.mobsim.framework.listeners.MobsimBeforeSimStepListener;
import org.matsim.core.router.util.TravelTime;
import org.matsim.vehicles.Vehicle;

import java.util.HashMap;
import java.util.Map;

public class CongestionAwareDrtTravelTime implements TravelTime, VehicleEntersTrafficEventHandler, LinkEnterEventHandler, MobsimBeforeSimStepListener {
    private final Network network;
    private final double capacityFactor;
    private final QSimFreeSpeedTravelTime qSimFreeSpeedTravelTime = new QSimFreeSpeedTravelTime(1);

    private final Map<Id<Link>, MutableInt> linkVehicleCountsMap = new HashMap<>();
    private final Map<Id<Link>, Double> linkTravelTimeMap = new HashMap<>();

    private final double interval = 30;  //Parameter to tune
    private final double turningPoint = 0.9; //Parameter to tune
    private final double penaltyFactor = 1.1; // Parameter to tune

    public CongestionAwareDrtTravelTime(Network network, double capacityFactor) {
        this.network = network;
        this.capacityFactor = capacityFactor;
    }

    public CongestionAwareDrtTravelTime(Network network) {
        this.network = network;
        this.capacityFactor = 1.0;
    }

    @Override
    public double getLinkTravelTime(Link link, double v, Person person, Vehicle vehicle) {
        return linkTravelTimeMap.getOrDefault(link.getId(), qSimFreeSpeedTravelTime.getLinkTravelTime(link, 0, null, null));
    }

    @Override
    public void handleEvent(LinkEnterEvent linkEnterEvent) {
        linkVehicleCountsMap.computeIfAbsent(linkEnterEvent.getLinkId(), v -> new MutableInt()).increment();
    }

    @Override
    public void handleEvent(VehicleEntersTrafficEvent vehicleEntersTrafficEvent) {
        linkVehicleCountsMap.computeIfAbsent(vehicleEntersTrafficEvent.getLinkId(), v -> new MutableInt()).increment();
    }

    @Override
    public void notifyMobsimBeforeSimStep(MobsimBeforeSimStepEvent mobsimBeforeSimStepEvent) {
        double now = mobsimBeforeSimStepEvent.getSimulationTime();
        if (now % interval == 0) {
            linkTravelTimeMap.clear();
            for (Id<Link> linkId : linkVehicleCountsMap.keySet()) {
                Link link = network.getLinks().get(linkId);
                double flowRate = linkVehicleCountsMap.get(linkId).doubleValue() / interval;
                double freeSpeedTravelTime = qSimFreeSpeedTravelTime.getLinkTravelTime(link, 0, null, null);
                double penalty = (flowRate - turningPoint * link.getCapacity()) / (link.getCapacity() * capacityFactor) * interval * penaltyFactor;
                //TODO double check capacity factor! Is it already scaled down in the network class when running the scenario?
                penalty = Math.max(0, penalty);
                double estimatedTravelTime = freeSpeedTravelTime + penalty;
                linkTravelTimeMap.put(linkId, estimatedTravelTime);
            }
            linkVehicleCountsMap.clear();
        }
    }

    @Override
    public void reset(int iteration) {
        linkVehicleCountsMap.clear();
    }
}
