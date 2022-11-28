package org.matsim.project.drtOperationStudy.mixedCase;

import org.matsim.api.core.v01.Id;
import org.matsim.contrib.drt.passenger.DrtRequest;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;

import java.util.List;
import java.util.Map;

public interface OnlineInserter {
    Id<DvrpVehicle> insert(DrtRequest request, Map<Id<DvrpVehicle>, List<TimetableEntry>> timetables,
                           Map<Id<DvrpVehicle>, MixedCaseDrtOptimizer.OnlineVehicleInfo> realTimeVehicleInfoMap);
}
