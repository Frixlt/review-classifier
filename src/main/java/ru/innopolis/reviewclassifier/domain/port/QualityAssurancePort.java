package ru.innopolis.reviewclassifier.domain.port;

import ru.innopolis.reviewclassifier.domain.model.IncidentCard;

/** Outbound port for forwarding critical review incidents to the Quality Assurance service. */
public interface QualityAssurancePort {
    void sendCriticalIncident(IncidentCard card);
}
