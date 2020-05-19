package com.julienviet.releaser;

import io.vertx.core.*;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import javax.management.*;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
@Mojo(name = "proxy", requiresProject = false)
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

    class ProxyMonitor implements ProxyMBean, Proxy.Listener {

      volatile String repositoryId;
      volatile int sent;
      volatile int errors;
      volatile Throwable lastError;
      final Map<String, String> inflight = new ConcurrentHashMap<>();

      @Override
      public String getProfileId() {
        return stagingProfileId;
      }
      @Override
      public int getPort() {
        return proxyPort;
      }
      @Override
      public String getRepositoryId() {
        return repositoryId;
      }
      @Override
      public int getSentCount() {
        return sent;
      }
      @Override
      public int getInProgressCount() {
        return inflight.size();
      }
      @Override
      public int getErrorCount() {
        return errors;
      }
      @Override
      public List<String> getInProgress() {
        return new ArrayList<>(inflight.keySet());
      }
      @Override
      public Throwable getLastError() {
        return lastError;
      }
      @Override
      public void onStagingCreate(String profileId) {
        System.out.println("Creating staging repo for " + profileId);
      }
      @Override
      public void onStagingSucceded(String profileId, String repoId) {
        System.out.println("Created staging repo " + repoId + " for " + profileId);
        repositoryId = repoId;
      }
      @Override
      public void onStagingFailed(String profileId, Throwable cause) {
        System.out.println("Could not create staging repo " + cause.getMessage());
        cause.printStackTrace();
      }
      @Override
      public void onResourceCreate(String uri) {
        inflight.put(uri, uri);
        report();
      }
      @Override
      public void onResourceSucceeded(String uri) {
        inflight.remove(uri);
        report();
      }
      @Override
      public void onResourceFailed(String uri, Throwable cause) {
        errors++;
        inflight.remove(uri);
        cause.printStackTrace();
        report();
      }
      private void report() {
        System.out.println("In progress " + inflight.size());
      }
    }

    ProxyMonitor monitor = new ProxyMonitor();
    try {
      StandardMBean mbean = new StandardMBean(monitor, ProxyMBean.class);
      MBeanServer server = ManagementFactory.getPlatformMBeanServer();
      server.registerMBean(mbean, new ObjectName("com.julienviet:type=StagingProxy,port=" + proxyPort));
    } catch (Exception e) {
      e.printStackTrace();
    }

    Proxy proxy = new Proxy(new ProxyOptions()
        .setPort(proxyPort)
        .setStagingProfileId(stagingProfileId)
        .setStagingUsername(stagingUsername)
        .setStagingPassword(stagingPassword), monitor);

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
