package org.matsim.project.drtSchoolTransportStudy.run.robustnessTest;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.dvrp.passenger.PassengerPickedUpEvent;
import org.matsim.contrib.dvrp.passenger.PassengerPickedUpEventHandler;

import java.util.HashMap;
import java.util.Map;

public class PickupTimeHandler implements PassengerPickedUpEventHandler {
    private static final Map<Id<Person>, Double> plannedPickUpTimeMap = new HashMap<>();

    @Override
    public void handleEvent(PassengerPickedUpEvent event) {
        plannedPickUpTimeMap.putIfAbsent(event.getPersonId(), event.getTime());
    }

    public Map<Id<Person>, Double> getPlannedPickUpTimeMap() {
        return plannedPickUpTimeMap;
    }
}
