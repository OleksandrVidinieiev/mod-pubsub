package org.folio.dao;

import io.vertx.core.Future;
import org.folio.rest.util.AuditMessageFilter;
import org.folio.rest.jaxrs.model.AuditMessage;
import org.folio.rest.jaxrs.model.AuditMessagePayload;

import java.util.List;
import java.util.Optional;

/**
 * Data access object for {@link AuditMessage}
 */
public interface AuditMessageDao {

  /**
   * Searches for all AuditMessages with specified filters
   *
   * @param auditMessageFilter AuditMessageFilter containing fields by which AuditMessages should be filtered
   * @param tenantId           tenant id
   * @return list of filtered AuditMessages
   */
  Future<List<AuditMessage>> getAuditMessages(AuditMessageFilter auditMessageFilter, String tenantId);

  /**
   * Saves {@link AuditMessage}
   *
   * @param auditMessage {@link AuditMessage} to save
   * @return saved AuditMessage
   */
  Future<AuditMessage> saveAuditMessage(AuditMessage auditMessage);

  /**
   * Saves {@link AuditMessagePayload}
   *
   * @param auditMessagePayload {@link AuditMessagePayload} to save
   * @param tenantId            tenant id
   * @return saved AuditMessagePayload
   */
  Future<AuditMessagePayload> saveAuditMessagePayload(AuditMessagePayload auditMessagePayload, String tenantId);

  /**
   * Searches for {@link AuditMessagePayload} by event id
   *
   * @param eventId  event id
   * @param tenantId tenant id
   * @return optional of AuditMessagePayload
   */
  Future<Optional<AuditMessagePayload>> getAuditMessagePayloadByEventId(String eventId, String tenantId);

}
