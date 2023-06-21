package org.matsim.project.drtRequestPatternIdentification.basicStructures;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Tools {
    public static Set<Id<Link>> collectRelevantLink(List<DrtDemand> drtDemands) {
        Set<Id<Link>> relevantLinks = new HashSet<>();
        for (DrtDemand demand : drtDemands) {
            relevantLinks.add(demand.fromLink().getId());
            relevantLinks.add(demand.toLink().getId());
        }
        return relevantLinks;
    }
}
