package org.matsim.project.drtOperationStudy.rollingHorizon;

import org.matsim.contrib.drt.extension.preplanned.optimizer.WaitForStopTask;
import org.matsim.contrib.drt.util.DrtEventsReaders;
import org.matsim.contrib.dvrp.passenger.PassengerDroppedOffEvent;
import org.matsim.contrib.dvrp.passenger.PassengerDroppedOffEventHandler;
import org.matsim.contrib.dvrp.passenger.PassengerPickedUpEvent;
import org.matsim.contrib.dvrp.passenger.PassengerPickedUpEventHandler;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;

import java.util.ArrayList;
import java.util.List;

public class DebuggerBasedOnEvents implements PassengerPickedUpEventHandler, PassengerDroppedOffEventHandler {
    public static void main(String[] args) {
        String eventsFile = "/Users/luchengqi/Documents/MATSimScenarios/Mielec/2014-02/output/rolling-horizon/ITERS/it.0/0.events.xml.gz";


        EventsManager eventsManager = EventsUtils.createEventsManager();
        eventsManager.addHandler(new DebuggerBasedOnEvents());
        MatsimEventsReader eventsReader = DrtEventsReaders.createEventsReader(eventsManager, WaitForStopTask.TYPE);
        eventsReader.readFile(eventsFile);
    }

    private int vehicleOccupancy = 0;
    private List<String> passengersOnboard = new ArrayList<>();
    private final String vehicleToCheckIdString = "drt_6";

    @Override
    public void handleEvent(PassengerPickedUpEvent passengerPickedUpEvent) {
        if (passengerPickedUpEvent.getVehicleId().toString().equals(vehicleToCheckIdString)) {
            vehicleOccupancy++;
            passengersOnboard.add(passengerPickedUpEvent.getPersonId().toString());
            System.out.println("At time = " + passengerPickedUpEvent.getTime());
            System.out.println("Passenger picked up. Passenger ID = " + passengerPickedUpEvent.getPersonId().toString());
            System.out.println("Vehicle occupancy = " + vehicleOccupancy);
            for (String passengerIdString: passengersOnboard) {
                System.out.println(passengerIdString);
            }
            System.out.println("===============================================================");
        }

    }

    @Override
    public void handleEvent(PassengerDroppedOffEvent passengerDroppedOffEvent) {
        if (passengerDroppedOffEvent.getVehicleId().toString().equals(vehicleToCheckIdString)) {
            vehicleOccupancy--;
            passengersOnboard.remove(passengerDroppedOffEvent.getPersonId().toString());
            System.out.println("At time = " + passengerDroppedOffEvent.getTime());
            System.out.println("Passenger dropped off. Passenger ID = " + passengerDroppedOffEvent.getPersonId().toString());
            System.out.println("Vehicle occupancy = " + vehicleOccupancy);
            for (String passengerIdString: passengersOnboard) {
                System.out.println(passengerIdString);
            }
            System.out.println("===============================================================");
        }
    }
}
