package org.folio.services;

import io.vertx.core.Future;
import org.folio.rest.util.OkapiConnectionParams;

/**
 * Security Manager Interface
 */
public interface SecurityManager {

  /**
   * Log in pub-sub user and save obtained token in the vertx context
   *
   * @param params okapi connection params
   * @return future with true if succeeded
   */
  Future<Boolean> loginPubSubUser(OkapiConnectionParams params);

  /**
   * Get JWT token for pub-sub user
   *
   * @param params okapi connection params
   * @return future with token
   */
  Future<String> getJWTToken(OkapiConnectionParams params);

  /**
   * Creates new pub-sub user if it doesn't exist and assigns all necessary permissions
   *
   * @param params okapi connection params
   * @return future with true if succeeded
   */
  Future<Boolean> createPubSubUser(OkapiConnectionParams params);
}
