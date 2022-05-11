package org.matsim.project.prebookingStudy.analysis;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.dvrp.passenger.PassengerPickedUpEvent;
import org.matsim.contrib.dvrp.passenger.PassengerPickedUpEventHandler;
import org.matsim.contrib.dvrp.passenger.PassengerRequestScheduledEvent;
import org.matsim.contrib.dvrp.passenger.PassengerRequestScheduledEventHandler;

import java.util.HashMap;
import java.util.Map;

/**
 * Analyze the delay between initial scheduled pickup time and the actual pickup time. This is useful for the DRT benchmark case.
 * <p>
 * Attention: Currently, we assume one person only has 1 school trip in the morning.
 */
public class DepartureDelayAnalysis implements PassengerRequestScheduledEventHandler, PassengerPickedUpEventHandler {
    Map<Id<Person>, Double> initialScheduledPickupTimeMap = new HashMap<>();
    Map<Id<Person>, Double> delaysMap = new HashMap<>();

    @Override
    public void handleEvent(PassengerRequestScheduledEvent passengerRequestScheduledEvent) {
        initialScheduledPickupTimeMap.put(passengerRequestScheduledEvent.getPersonId(), passengerRequestScheduledEvent.getPickupTime());
    }

    @Override
    public void handleEvent(PassengerPickedUpEvent passengerPickedUpEvent) {
        Id<Person> personId = passengerPickedUpEvent.getPersonId();
        double initialScheduledPickupTime = initialScheduledPickupTimeMap.get(personId);
        double actualPickupTime = passengerPickedUpEvent.getTime();
        double delay = actualPickupTime - initialScheduledPickupTime;
        delaysMap.put(personId, delay);
    }

    @Override
    public void reset(int iteration) {
        initialScheduledPickupTimeMap.clear();
        delaysMap.clear();
    }

    public Map<Id<Person>, Double> getDelaysMap() {
        return delaysMap;
    }

    public Map<Id<Person>, Double> getInitialScheduledPickupTimeMap() {
        return initialScheduledPickupTimeMap;
    }

    public double getAverageDelay() {
        if (delaysMap.isEmpty()) {
            return Double.NaN;
        }
        return delaysMap.values().stream().mapToDouble(d -> d).average().orElse(-1);
    }
}
