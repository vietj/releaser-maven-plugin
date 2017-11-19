package com.julienviet.releaser;

import io.vertx.core.*;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.util.concurrent.*;

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

    Proxy proxy = new Proxy(new ProxyOptions()
        .setPort(proxyPort)
        .setStagingProfileId(stagingProfileId)
        .setStagingUsername(stagingUsername)
        .setStagingPassword(stagingPassword), new Proxy.Listener() {
      @Override
      public void onStagingCreate(String profileId) {
        System.out.println("Creating staging repo for " + profileId);
      }
      @Override
      public void onStagingSucceded(String profileId, String repoId) {
        System.out.println("Created staging repo " + repoId + " for " + profileId);
      }
      @Override
      public void onSuccessFailed(String profileId, Throwable cause) {
        System.out.println("Could not create staging repo");
        cause.printStackTrace();
      }
      int inflight;
      @Override
      public void onResourceCreate(String uri) {
        inflight++;
        report();
      }
      @Override
      public void onResourceSucceeded(String uri) {
        inflight--;
        report();
      }
      @Override
      public void onResourceFailed(String uri, Throwable cause) {
        inflight--;
        cause.printStackTrace();
        report();
      }
      void report() {
        System.out.println("Current status: " + inflight);
      }
    });

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
      System.out.println("Proxy started, you can deploy to http://localhost:" + proxyPort + "");
      CountDownLatch latch = new CountDownLatch(1);
      latch.await();
    } catch (Exception ignore) {
    } finally {
      vertx.close();
    }
  }
}
