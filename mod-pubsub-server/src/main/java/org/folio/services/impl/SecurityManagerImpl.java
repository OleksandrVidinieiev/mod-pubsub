package org.folio.services.impl;

import com.google.common.io.Resources;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.folio.HttpStatus;
import org.folio.dao.PubSubUserDao;
import org.folio.rest.util.OkapiConnectionParams;
import org.folio.services.SecurityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static java.lang.String.format;
import static org.folio.rest.util.OkapiConnectionParams.OKAPI_TOKEN_HEADER;
import static org.folio.rest.util.RestUtil.doRequest;

@Component
public class SecurityManagerImpl implements SecurityManager {

  private static final Logger LOGGER = LoggerFactory.getLogger(SecurityManagerImpl.class);

  private static final String LOGIN_URL = "/authn/login";
  private static final String USERS_URL = "/users";
  private static final String CREDENTIALS_URL = "/authn/credentials";
  private static final String PERMISSIONS_URL = "/perms/users";
  private static final String PERMISSIONS_FILE_PATH = "permissions/pubsub-user-permissions.csv";
  private static final String PUB_SUB_USERNAME = "pub-sub";

  private PubSubUserDao pubSubUserDao;

  public SecurityManagerImpl(@Autowired PubSubUserDao pubSubUserDao) {
    this.pubSubUserDao = pubSubUserDao;
  }

  @Override
  public Future<Boolean> loginPubSubUser(OkapiConnectionParams params) {
    return pubSubUserDao.getPubSubUserCredentials(params.getTenantId())
      .compose(userCredentials -> doRequest(userCredentials.encode(), LOGIN_URL, HttpMethod.POST, params))
      .compose(response -> {
        if (response.statusCode() == HttpStatus.HTTP_CREATED.toInt()) {
          LOGGER.info("Logged in pub-sub user");
          return pubSubUserDao.savePubSubJWTToken(response.getHeader(OKAPI_TOKEN_HEADER), params.getTenantId());
        }
        LOGGER.error("pub-sub user was not logged in, received status {}", response.statusCode());
        return Future.succeededFuture(false);
      });
  }

  @Override
  public Future<String> getJWTToken(String tenantId) {
    return pubSubUserDao.getPubSubJWTToken(tenantId);
  }

  @Override
  public Future<Boolean> createPubSubUser(OkapiConnectionParams params) {
    return existsPubSubUser(params)
      .compose(id -> {
        if (StringUtils.isNotEmpty(id)) {
          return  addPermissions(id, params);
        } else {
          return createUser(params)
            .compose(userId -> saveCredentials(userId, params))
            .compose(userId -> assignPermissions(userId, params));
        }
      });
  }

  private Future<String> existsPubSubUser(OkapiConnectionParams params) {
    String query = "?query=username=" + PUB_SUB_USERNAME;
    return doRequest(null, USERS_URL + query, HttpMethod.GET, params)
      .compose(response -> {
        Future<String> future = Future.future();
        if (response.statusCode() == HttpStatus.HTTP_OK.toInt()) {
          response.bodyHandler(buf -> {
            JsonObject usersCollection = new JsonObject(buf.toString());
            JsonArray users = usersCollection.getJsonArray("users");
            if (users.size() > 0) {
              future.complete(users.getJsonObject(0).getString("id"));
            } else {
              future.complete();
            }
          });
        } else {
          LOGGER.error("Failed request on GET users. Received status code {}", response.statusCode());
          future.complete();
        }
        return future;
      });
  }

  private Future<String> createUser(OkapiConnectionParams params) {
    String id = UUID.randomUUID().toString();
    JsonObject body = new JsonObject()
      .put("id", id)
      .put("username", PUB_SUB_USERNAME)
      .put("active", true);
    return doRequest(body.encode(), USERS_URL, HttpMethod.POST, params)
      .compose(response -> {
        Future<String> future = Future.future();
        if (response.statusCode() == HttpStatus.HTTP_CREATED.toInt()) {
          LOGGER.info("Created pub-sub user");
          future.complete(id);
        } else {
          String errorMessage = format("Failed to create pub-sub user. Received status code %s", response.statusCode());
          LOGGER.error(errorMessage);
          future.fail(errorMessage);
        }
        return future;
      });
  }

  private Future<String> saveCredentials(String userId, OkapiConnectionParams params) {
    return pubSubUserDao.getPubSubUserCredentials(params.getTenantId())
      .compose(credentials -> {
        credentials.put("userId", userId);
        return doRequest(credentials.encode(), CREDENTIALS_URL, HttpMethod.POST, params)
          .compose(response -> {
            Future<String> future = Future.future();
            if (response.statusCode() == HttpStatus.HTTP_CREATED.toInt()) {
              LOGGER.info("Saved pub-sub user credentials");
              future.complete(userId);
            } else {
              String errorMessage = format("Failed to save pub-sub user credentials. Received status code %s", response.statusCode());
              LOGGER.error(errorMessage);
              future.fail(errorMessage);
            }
            return future;
          });
      });
  }

  private Future<Boolean> assignPermissions(String userId, OkapiConnectionParams params) {
    List<String> permissions = readPermissionsFromResource(PERMISSIONS_FILE_PATH);
    if (CollectionUtils.isEmpty(permissions)) {
      LOGGER.info("No permissions found to assign to pub-sub user");
      return Future.succeededFuture(false);
    }
    JsonObject requestBody = new JsonObject()
      .put("id", UUID.randomUUID().toString())
      .put("userId", userId)
      .put("permissions", new JsonArray(permissions));
    return doRequest(requestBody.encode(), PERMISSIONS_URL, HttpMethod.POST, params)
      .compose(response -> {
        Future<Boolean> future = Future.future();
        if (response.statusCode() == HttpStatus.HTTP_CREATED.toInt()) {
          LOGGER.info("Added permissions [{}] for pub-sub user", StringUtils.join(permissions, ","));
          future.complete(true);
        } else {
          String errorMessage = format("Failed to add permissions for pub-sub user. Received status code %s", response.statusCode());
          LOGGER.error(errorMessage);
          future.complete(false);
        }
        return future;
      });
  }

  private Future<Boolean> addPermissions(String userId, OkapiConnectionParams params) {
    List<String> permissions = readPermissionsFromResource(PERMISSIONS_FILE_PATH);
    if (CollectionUtils.isEmpty(permissions)) {
      LOGGER.info("No permissions found to add for pub-sub user");
      return Future.succeededFuture(false);
    }
    List<Future> futures = new ArrayList<>();
    String permUrl = PERMISSIONS_URL + "/" + userId + "/permissions?indexField=userId";
    permissions.forEach(permission -> {
      JsonObject requestBody = new JsonObject()
        .put("permissionName",permission);
      futures.add(doRequest(requestBody.encode(), permUrl, HttpMethod.POST, params)
        .compose(response -> {
          Future<Boolean> future = Future.future();
          if (response.statusCode() == HttpStatus.HTTP_OK.toInt()) {
            LOGGER.info("Added permission {} for pub-sub user", permission);
            future.complete(true);
          } else {
            String errorMessage = format("Failed to add permission %s for pub-sub user. Received status code %s", permission, response.statusCode());
            LOGGER.error(errorMessage);
            future.complete(false);
          }
          return future;
        }));
    });
    return CompositeFuture.all(futures).map(true);
  }

  private List<String> readPermissionsFromResource(String path) {
    List<String> permissions = new ArrayList<>();
    URL url = Resources.getResource(path);
    try {
      permissions = Resources.readLines(url, StandardCharsets.UTF_8);
    } catch (IOException e) {
      LOGGER.error("Error reading permissions from {}", e, path);
    }
    return permissions;
  }

}