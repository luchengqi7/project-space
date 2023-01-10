package org.matsim.project.drtOperationStudy.mixedCase;

import org.matsim.api.core.v01.Id;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;

import java.util.List;
import java.util.Map;

public interface PrebookedRequestsSolver {
    MixedCaseDrtOptimizer.FleetSchedules calculate(MixedCaseDrtOptimizer.FleetSchedules previousSchedules,
                                                   Map<Id<DvrpVehicle>, MixedCaseDrtOptimizer.OnlineVehicleInfo> onlineVehicleInfoMap,
                                                   List<MixedCaseDrtOptimizer.GeneralRequest> newRequests,
                                                   double time);
}
