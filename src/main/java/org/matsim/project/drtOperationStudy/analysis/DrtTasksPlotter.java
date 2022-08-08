package org.matsim.project.drtOperationStudy.analysis;

import org.apache.commons.lang3.mutable.MutableInt;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.ActivityEndEvent;
import org.matsim.api.core.v01.events.ActivityStartEvent;
import org.matsim.api.core.v01.events.handler.ActivityEndEventHandler;
import org.matsim.api.core.v01.events.handler.ActivityStartEventHandler;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.drt.extension.preplanned.optimizer.WaitForStopTask;
import org.matsim.contrib.drt.schedule.DefaultDrtStopTask;
import org.matsim.contrib.drt.schedule.DrtStayTask;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.passenger.PassengerDroppedOffEvent;
import org.matsim.contrib.dvrp.passenger.PassengerDroppedOffEventHandler;
import org.matsim.contrib.dvrp.passenger.PassengerPickedUpEvent;
import org.matsim.contrib.dvrp.passenger.PassengerPickedUpEventHandler;
import org.matsim.contrib.dvrp.vrpagent.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DrtTasksPlotter implements TaskEndedEventHandler, TaskStartedEventHandler,
        PassengerPickedUpEventHandler, PassengerDroppedOffEventHandler {
    Map<Id<DvrpVehicle>, MutableInt> vehicleOccupancyTracker = new HashMap<>();
    Map<Id<DvrpVehicle>, DrtTaskInformation> startedTasks = new HashMap<>();
    List<DrtTaskInformation> drtTasksEntries = new ArrayList<>();

    public static class DrtTaskInformation {
        private final String taskName;
        private final Id<Link> linkId;
        private final double startTime;
        private double endTime;
        private final Id<DvrpVehicle> vehicleId;
        private final int occupancy;

        private DrtTaskInformation(String taskName, Id<Link> linkId, double startTime, Id<DvrpVehicle> vehicleId, int occupancy) {
            this.taskName = taskName;
            this.linkId = linkId;
            this.startTime = startTime;
            this.vehicleId = vehicleId;
            this.occupancy = occupancy;
        }

        void setEndTime(double endTime) {
            this.endTime = endTime;
        }

        public String getTaskName() {
            return taskName;
        }

        public Id<Link> getLinkId() {
            return linkId;
        }

        public double getStartTime() {
            return startTime;
        }

        public double getEndTime() {
            return endTime;
        }

        public Id<DvrpVehicle> getVehicleId() {
            return vehicleId;
        }

        public int getOccupancy() {
            return occupancy;
        }
    }

    @Override
    public void handleEvent(TaskStartedEvent taskStartedEvent) {
        Id<DvrpVehicle> vehicleId = taskStartedEvent.getDvrpVehicleId();
        Id<Link> linkId = taskStartedEvent.getLinkId();
        double time = taskStartedEvent.getTime();

        // Stay task
        if (taskStartedEvent.getTaskType().equals(DrtStayTask.TYPE)) {
            if (!vehicleOccupancyTracker.containsKey(vehicleId)) {
                vehicleOccupancyTracker.put(vehicleId, new MutableInt());
            }
            assert vehicleOccupancyTracker.get(vehicleId).intValue() == 0;
            startedTasks.put(vehicleId,
                    new DrtTaskInformation(DrtStayTask.TYPE.name(), linkId, time, vehicleId, 0));
        }

        int occupancy = vehicleOccupancyTracker.get(vehicleId).intValue();
        // Stop task
        if (taskStartedEvent.getTaskType().equals(DefaultDrtStopTask.TYPE)) {
            startedTasks.put(vehicleId,
                    new DrtTaskInformation(DefaultDrtStopTask.TYPE.name(), linkId, time, vehicleId, occupancy));
        }

        // Wait for stop task
        if (taskStartedEvent.getTaskType().equals(WaitForStopTask.TYPE)) {
            startedTasks.put(vehicleId,
                    new DrtTaskInformation(WaitForStopTask.TYPE.name(), linkId, time, vehicleId, occupancy));
        }
    }

    @Override
    public void handleEvent(TaskEndedEvent taskEndedEvent) {
        if (taskEndedEvent.getTaskType().equals(WaitForStopTask.TYPE) ||
                taskEndedEvent.getTaskType().equals(DefaultDrtStopTask.TYPE) ||
                taskEndedEvent.getTaskType().equals(DrtStayTask.TYPE)) {
            Id<DvrpVehicle> vehicleId = taskEndedEvent.getDvrpVehicleId();
            DrtTaskInformation drtTaskInformation = startedTasks.get(vehicleId);
            drtTaskInformation.setEndTime(taskEndedEvent.getTime());
            drtTasksEntries.add(drtTaskInformation);
            startedTasks.remove(vehicleId);
        }
    }


    @Override
    public void handleEvent(PassengerPickedUpEvent passengerPickedUpEvent) {
        Id<DvrpVehicle> vehicleId = passengerPickedUpEvent.getVehicleId();
        vehicleOccupancyTracker.get(vehicleId).increment();
    }

    @Override
    public void handleEvent(PassengerDroppedOffEvent passengerDroppedOffEvent) {
        Id<DvrpVehicle> vehicleId = passengerDroppedOffEvent.getVehicleId();
        vehicleOccupancyTracker.get(vehicleId).decrement();
    }

    @Override
    public void reset(int iteration) {
        vehicleOccupancyTracker.clear();
        startedTasks.clear();
        drtTasksEntries.clear();
    }

    public List<DrtTaskInformation> getDrtTasksEntries() {
        return drtTasksEntries;
    }
}
