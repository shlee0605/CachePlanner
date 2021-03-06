package edu.duke.cacheplanner.generator;

import java.util.List;

import edu.duke.cacheplanner.data.Column;
import edu.duke.cacheplanner.data.Dataset;
import edu.duke.cacheplanner.data.QueryDistribution;
import edu.duke.cacheplanner.listener.ListenerManager;
import edu.duke.cacheplanner.query.AbstractQuery;
import edu.duke.cacheplanner.queue.ExternalQueue;

public abstract class AbstractQueryGenerator {
  //Distribution over query arrival rate (per second)
  protected double lambda;
  protected int queueId;
  protected String name;


  protected List<Dataset> datasets;
  protected QueryDistribution queryDistribution;
  protected ExternalQueue externalQueue;
  protected ListenerManager listenerManager;
  protected Thread generatorThread;
  protected boolean started = false;

  public AbstractQueryGenerator(double lamb, int id, String name) {
    lambda = lamb;
    queueId = id;
    this.name = name;
    generatorThread = createThread();
  }

  abstract public Thread createThread();

  /**
   * calculate the delayed time using poisson arrival
   * inter query arrival times(in seconds) would be given by mean lambda
   */
  public double getPoissonDelay() {
    double mean = 1.0 / (1000 * lambda); // convert the number in milliseconds 
    return Math.log(Math.random()) / -mean;
  }

  public void start() {
    started = true;
    generatorThread.start();
  }

  public void stop() throws InterruptedException {
    if (!started) {
      throw new IllegalStateException("cannot be done because a listener has not yet started!");
    }
    started = false;
    generatorThread.join();
  }

  public void setListenerManager(ListenerManager manager) {
    listenerManager = manager;
  }

  public void setDatasets(List<Dataset> data) {
    datasets = data;
  }

  public void setExternalQueue(ExternalQueue queue) {
    externalQueue = queue;
    externalQueue.setListenerManager(listenerManager);
  }

  public void setQueryDistribution(QueryDistribution distribution) {
    queryDistribution = distribution;
  }

  public int getQueueId() {
    return queueId;
  }
  
  public String getName() {
	  return name;
  }

  public Dataset getDataset(String name) {
    for (Dataset d : datasets) {
      if (d.getName().equals(name)) {
        return d;
      }
    }
    return null;
  }
  
  public Column getColumn(Dataset data, String colName) {
    for (Column c : data.getColumns()) {
      if (colName.equals(c.getColName())) {
        return c;
      }
    }
    return null;
  }

  /**
   * generate the query and put it into the one of the ExternalQueue,
   * return the id of the chosen queue
   */
  public abstract AbstractQuery generateQuery();

}
