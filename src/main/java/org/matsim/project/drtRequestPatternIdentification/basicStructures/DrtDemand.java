package org.matsim.project.drtRequestPatternIdentification.basicStructures;

import org.matsim.api.core.v01.network.Link;

public record DrtDemand(String tripIdString, Link fromLink, Link toLink, double departureTime) {
}
