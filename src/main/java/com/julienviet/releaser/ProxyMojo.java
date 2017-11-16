package com.julienviet.releaser;

import io.vertx.core.*;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
@Mojo(name = "proxy", aggregator = true)
public class ProxyMojo extends AbstractMojo {

  @Parameter(property = "stagingProfileId")
  private String stagingProfileId;

  @Parameter(property = "stagingUsername")
  private String stagingUsername;

  @Parameter(property = "stagingPassword")
  private String stagingPassword;

  @Parameter(property = "proxyPort", defaultValue = "8080")
  private int proxyPort;

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {

    if (stagingUsername == null) {
      throw new MojoFailureException("Your must provide -DstagingUsername=XXX");
    }
    if (stagingPassword == null) {
      throw new MojoFailureException("Your must provide -DstagingPassword=XXX");
    }
    if (stagingProfileId == null) {
      throw new MojoFailureException("Your must provide -DstagingProfileId=XXX");
    }

    Vertx vertx = Vertx.vertx();

    Proxy proxy = new Proxy()
        .port(proxyPort)
        .stagingProfileId(stagingProfileId)
        .stagingUsername(stagingUsername)
        .stagingPassword(stagingPassword);

    CompletableFuture<Void> sync = new CompletableFuture<>();
    vertx.deployVerticle(proxy, ar -> {
      if (ar.succeeded()) {
        sync.complete(null);
      } else {
        sync.completeExceptionally(ar.cause());
      }
    });

    try {
      sync.get();
      System.out.println("Proxy started, you can deploy to http://localhost:" + proxyPort +
              "/repo and use http://localhost/stage");
      CountDownLatch latch = new CountDownLatch(1);
      latch.await();
    } catch (Exception ignore) {
    } finally {
      vertx.close();
    }
  }
}
