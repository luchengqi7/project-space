package org.matsim.project.drtRequestPatternIdentification;

import org.matsim.api.core.v01.network.Link;

record DrtDemand(String tripIdString, Link fromLink, Link toLink, double departureTime) {
}
