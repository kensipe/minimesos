package com.containersol.minimesos.marathon;

import com.containersol.minimesos.MinimesosException;
import com.containersol.minimesos.cluster.Marathon;
import com.containersol.minimesos.cluster.MesosCluster;
import com.containersol.minimesos.cluster.ZooKeeper;
import com.containersol.minimesos.config.AppConfig;
import com.containersol.minimesos.config.MarathonConfig;
import com.containersol.minimesos.container.AbstractContainerImpl;
import com.containersol.minimesos.docker.DockerClientFactory;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.JsonNode;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpStatus;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static com.jayway.awaitility.Awaitility.await;

/**
 * Marathon container. Marathon is a cluster-wide init and control system for services in cgroups or Docker containers.
 */
public class MarathonContainer extends AbstractContainerImpl implements Marathon {

    private static Logger LOGGER = LoggerFactory.getLogger(MarathonContainer.class);

    private final MarathonConfig config;

    private ZooKeeper zooKeeper;

    public MarathonContainer(ZooKeeper zooKeeper) {
        this(zooKeeper, new MarathonConfig());
    }

    public MarathonContainer(ZooKeeper zooKeeper, MarathonConfig config) {
        super();
        this.zooKeeper = zooKeeper;
        this.config = config;
    }

    public MarathonContainer(MesosCluster cluster, String uuid, String containerId) {
        super(cluster, uuid, containerId);
        this.config = new MarathonConfig();
    }

    @Override
    public String getRole() {
        return "marathon";
    }

    @Override
    protected void pullImage() {
        pullImage(config.getImageName(), config.getImageTag());
    }

    public void setZooKeeper(ZooKeeper zooKeeper) {
        this.zooKeeper = zooKeeper;
    }

    @Override
    protected CreateContainerCmd dockerCommand() {
        ExposedPort exposedPort = ExposedPort.tcp(MarathonConfig.MARATHON_PORT);
        Ports portBindings = new Ports();
        if (getCluster().isExposedHostPorts()) {
            portBindings.bind(exposedPort, Ports.Binding(MarathonConfig.MARATHON_PORT));
        }
        return DockerClientFactory.build().createContainerCmd(config.getImageName() + ":" + config.getImageTag())
                .withName( getName() )
                .withExtraHosts("minimesos-zookeeper:" + this.zooKeeper.getIpAddress())
                .withCmd("--master", "zk://minimesos-zookeeper:2181/mesos", "--zk", "zk://minimesos-zookeeper:2181/marathon")
                .withExposedPorts(exposedPort)
                .withPortBindings(portBindings);
    }

    /**
     * Deploys a Marathon app by JSON string
     *
     * @param marathonJson JSON string
     */
    @Override
    public void deployApp(String marathonJson) {
        String marathonEndpoint = getMarathonEndpoint();
        try {
            byte[] app = marathonJson.getBytes(Charset.forName("UTF-8"));
            HttpResponse<JsonNode> response = Unirest.post(marathonEndpoint + "/v2/apps").header("accept", "application/json").body(app).asJson();
            JSONObject deployResponse = response.getBody().getObject();
            if (response.getStatus() == HttpStatus.SC_CREATED) {
                LOGGER.debug(deployResponse.toString());
            } else {
                throw new MinimesosException("Marathon did not accept the app: " + deployResponse);
            }
        } catch (UnirestException e) {
            String msg = "Could not deploy app on Marathon at " + marathonEndpoint + " => " + e.getMessage();
            LOGGER.error(msg);
            throw new MinimesosException(msg, e);
        }
        LOGGER.debug(String.format("Installing an app on marathon %s", getMarathonEndpoint()));
    }

    /**
     * Kill all apps that are currently running.
     */
    @Override
    public void killAllApps() {
        String marathonEndpoint = getMarathonEndpoint();
        JSONObject appsResponse;
        try {
            appsResponse = Unirest.get(marathonEndpoint + "/v2/apps").header("accept", "application/json").asJson().getBody().getObject();
            if (appsResponse.length() == 0) {
                return;
            }
        } catch (UnirestException e) {
            LOGGER.error("Could not retrieve apps from Marathon at " + marathonEndpoint);
            return;
        }

        JSONArray apps = appsResponse.getJSONArray("apps");
        for (int i = 0; i < apps.length(); i++) {
            JSONObject app = apps.getJSONObject(i);
            String appId = app.getString("id");
            try {
                Unirest.delete(marathonEndpoint + "/v2/apps" + appId).asJson();
            } catch (UnirestException e) {
                LOGGER.error("Could not delete app " + appId + " at " + marathonEndpoint);
            }
        }
    }

    public String getMarathonEndpoint() {
        return "http://" + getIpAddress() + ":" + MarathonConfig.MARATHON_PORT;
    }

    public void waitFor() {
        LOGGER.debug("Waiting for Marathon to be ready at " + getMarathonEndpoint());
        await("Marathon did not start responding").atMost(getCluster().getClusterConfig().getTimeout(), TimeUnit.SECONDS).pollDelay(1, TimeUnit.SECONDS).until(new MarathonApiIsReady());
    }

    public MarathonConfig getConfig() {
        return config;
    }

    private class MarathonApiIsReady implements Callable<Boolean> {
        @Override
        public Boolean call() throws Exception {
            try {
                Unirest.get(getMarathonEndpoint() + "/v2/apps").header("accept", "application/json").asJson().getBody().getObject();
            } catch (UnirestException e) {
                return false;
            }
            return true;
        }
    }

    /**
     * If Marathon configuration requires, installs the applications
     */
    @Override
    public void installMarathonApps() {

        waitFor();

        List<AppConfig> apps = getConfig().getApps();
        for (AppConfig app : apps) {
            try {

                InputStream json = MesosCluster.getInputStream(app.getMarathonJson());
                if (json != null) {
                    deployApp(IOUtils.toString(json, "UTF-8"));
                } else {
                    throw new MinimesosException("Failed to find content of " + app.getMarathonJson());
                }

            } catch (IOException ioe) {
                throw new MinimesosException("Failed to load JSON from " + app.getMarathonJson(), ioe);
            }
        }

    }
}
