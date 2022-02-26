package org.matsim.project.prebookingStudy.jsprit.utils;

import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.router.util.TravelTime;
import org.matsim.vehicles.Vehicle;

public class FreeSpeedTravelTimeWithRoundingUp implements TravelTime {
    @Override
    public double getLinkTravelTime(Link link, double time, Person person, Vehicle vehicle) {
        double freeSpeedTravelTime = link.getLength() / link.getFreespeed(time);
        return Math.ceil(freeSpeedTravelTime);
    }
}
