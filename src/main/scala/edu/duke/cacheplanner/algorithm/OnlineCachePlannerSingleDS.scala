/**
 *
 */
package edu.duke.cacheplanner.algorithm

import scala.collection.JavaConversions._
import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.HashMap
import scala.collection.mutable.Map
import edu.duke.cacheplanner.algorithm.singleds.MMFBatchAnalyzer
import edu.duke.cacheplanner.algorithm.singleds.PFBatchAnalyzer
import edu.duke.cacheplanner.conf.ConfigManager
import edu.duke.cacheplanner.data.Column
import edu.duke.cacheplanner.data.Dataset
import edu.duke.cacheplanner.listener._
import edu.duke.cacheplanner.query.QueryUtil
import edu.duke.cacheplanner.query.SingleDatasetQuery
import edu.duke.cacheplanner.queue.ExternalQueue
import scala.collection.mutable.ListBuffer
import edu.duke.cacheplanner.algorithm.singleds.AbstractSingleDSBatchAnalyzer

/**
 * @author mayuresh
 *
 */
class OnlineCachePlannerSingleDS(setup: Boolean, manager: ListenerManager, 
    queues: java.util.List[ExternalQueue], data: java.util.List[Dataset], 
    config: ConfigManager) extends AbstractCachePlanner(
        setup, manager, queues, data, config) {

  val batchTime = config.getPlannerBatchTime();

  override def initPlannerThread(): Thread = {
    new Thread("OnlineCachePlanner") {

      /**
       * Multiple setups of algorithms are captured by this interface
       */
      trait CachePartitionSetup {
        def init() = {}	//any initialization on algorithm and other data structures
        def run() = {}	//called on each invocation See @run of Thread
      }

      /**
       * When we want a performance optimal subject to fairness
       */
      class FairShareSetup extends CachePartitionSetup {
        
        var cachedDatasets = List[Dataset]()
        var cacheSize = config.getCacheSize().doubleValue()

        override def init() = {}

        override def run() = {
          val batch = fetchNextBatch
          val datasetsToCache = runAlgorithm(batch, cachedDatasets, cacheSize)
          scheduleBatch(batch, cachedDatasets, datasetsToCache)
          cachedDatasets = datasetsToCache
        }

      }
      
      /**
       * When we want a performance optimal solution
       */
      class UnfairShareSetup extends FairShareSetup {

        override def init() = {
          super.init
          algo.setSingleTenant(true)
        }

      }

      /**
       * When we partition the cache among all tenants probabilistically
       * i.e. a tenant gets to own the entire batch with certain probability
       */
      class ProbabilisticPartitionSetup extends CachePartitionSetup {

        var cachedDatasets = List[Dataset]()
        var cacheSize = config.getCacheSize().doubleValue()
        var queueProbability = scala.collection.mutable.Map[Int, Double]()

        override def init() = {
          var totalWeight = 0
          queues.foreach(q => totalWeight = totalWeight + q.getWeight())
          queues.foreach(q => queueProbability(q.getId) = q.getWeight / totalWeight)
        }

        override def run() = {
          // pick a queue at random to favor
          val rnd = Math.random()
          var cumulative = 0d
          var luckyQueue = 0 
          queueProbability.foreach(t => {
            cumulative += t._2; 
            if(rnd < cumulative) {luckyQueue = t._1}
          })

          // run algo only on queries from luckyQueue, but schedule all queries
          val batch = fetchNextBatch
          var filteredBatch = 
            scala.collection.mutable.ListBuffer[SingleDatasetQuery]()
          batch.foreach(t => if(t.getQueueID == luckyQueue) {
            filteredBatch.add(t)
          })
          val datasetsToCache = runAlgorithm(filteredBatch.toList, 
              cachedDatasets, cacheSize)
          scheduleBatch(batch, cachedDatasets, datasetsToCache)
          cachedDatasets = datasetsToCache
        }
      }

      /**
       * When we partition the cache among all tenants
       */
      class PhysicalPartitionSetup extends ProbabilisticPartitionSetup {
        
        var cachePerQueue = scala.collection.mutable.Map[Int, Double]()
        var cachedDatasetsPerQueue = 
          scala.collection.mutable.Map[Int, List[Dataset]]()

        override def init() = {
          super.init()
          queueProbability.foreach(t => {
            cachePerQueue(t._1) = t._2 * cacheSize
            cachedDatasetsPerQueue(t._1) = List[Dataset]()
          })
        }

        override def run() = {
          val batch = fetchNextBatch
          var batchPerQueue = 
            scala.collection.mutable.Map[Int, scala.collection.mutable.ListBuffer[SingleDatasetQuery]]()
          batch.foreach(q => {
            val queue = q.getQueueID;
            if(batchPerQueue(queue) == null) {
              batchPerQueue(queue) = 
                new scala.collection.mutable.ListBuffer[SingleDatasetQuery]()
            }
            batchPerQueue(queue).add(q)
          })
          
          batchPerQueue.foreach(q => {
            val datasetsToCache = runAlgorithm(q._2.toList, 
                cachedDatasetsPerQueue(q._1), cachePerQueue(q._1))
            scheduleBatch(q._2.toList, cachedDatasetsPerQueue(q._1), 
                datasetsToCache)
            cachedDatasetsPerQueue(q._1) = datasetsToCache            
          })
        }
        
      }

      /**
       * Algorithm specifications follow.
       */
      val algo = buildAlgo
      if(config.getCacheState().equals("warm")) {
        algo.setWarmCache(true)
      }

      val setup = buildSetup
      setup.init()

      def buildAlgo(): AbstractSingleDSBatchAnalyzer = {
        if(config.getAlgorithmName().equals("MMF")) {
          return new MMFBatchAnalyzer(data)
        } else {
          return new PFBatchAnalyzer(data)
        }
      }

      def buildSetup: CachePartitionSetup = {
        val confValue = config.getCachePartitioningStrategy()
        if(confValue.equals("shareFairly")) {
          new FairShareSetup()
        } else if(confValue.equals("shareUnfairly")) {
          new UnfairShareSetup()
        } else if(confValue.equals("partitionProbabilistically")) {
          new ProbabilisticPartitionSetup()
        } else if(confValue.equals("partitionPhysically")) {
          new PhysicalPartitionSetup()
        }
        new FairShareSetup()
      }

      /**
       * Returns next batch compiled from all queues
       */
      def fetchNextBatch(): List[SingleDatasetQuery] = {
            var batch = 
              scala.collection.mutable.ListBuffer[SingleDatasetQuery]()
            for (queue <- externalQueues.toList) {
              queue.fetchABatch().toList.foreach(
                  q =>  {
                    batch += q.asInstanceOf[SingleDatasetQuery]
                  })
            }
            return batch.toList
      }

      /**
       * Runs algorithm on given batch with a list of datasets already in cache, 
       * and a given cache size.
       * Returns new allocation i.e. a list of datasets to be cached
       */
      def runAlgorithm(batch: List[SingleDatasetQuery], 
          cachedDatasets: List[Dataset], cacheSize: Double): List[Dataset] = {
            val javaBatch: java.util.List[SingleDatasetQuery] = batch
            val javaCachedDatasets: java.util.List[Dataset] = cachedDatasets
            val datasetsToCache : List[Dataset] = algo.analyzeBatch(
                javaBatch, javaCachedDatasets, cacheSize).toList      
            datasetsToCache
      }

      /**
       * Schedules a batch of queries. A list of datasets already in cache and 
       * a list of datasets that should be in cache is given. 
       * It first changes the state of cache and then runs the queries. 
       */
      def scheduleBatch(batch: List[SingleDatasetQuery], 
          cachedDatasets: List[Dataset], datasetsToCache: List[Dataset]) = {
            println("cached from previous")
            cachedDatasets.foreach(c => println(c.getName()))
                
            println("datasets to cache from algorithm:")
            datasetsToCache.foreach(c=> println(c.getName()))
            
            //initialize drop & cache candidates to fire the query
            var dropCandidate : ListBuffer[Dataset] = new ListBuffer[Dataset]()
            cachedDatasets.foreach(c => dropCandidate += c)
            var cacheCandidate : ListBuffer[Dataset] = new ListBuffer[Dataset]()
            datasetsToCache.foreach(c => cacheCandidate += c)
            
            for (cache: Dataset <- cacheCandidate) {
              var matching = false
              var droppingCol: Dataset = null
              for (drop: Dataset <- dropCandidate) {
                if(cache.equals(drop)) {
                  matching = true
                  droppingCol = drop
                }
              }
              if(matching) {
                cacheCandidate -= cache
                dropCandidate -= droppingCol
                
                manager.postEvent(new DatasetRetainedInCache(cache))
                
              }
            }
            
            println("cache candidate:")
            cacheCandidate.foreach(c => println(c.getName()))
            println("drop candidate:")
            dropCandidate.foreach(c=> println(c.getName()))
            
//            cachedDatasets = new ListBuffer[Dataset]()
//            datasetsToCache.foreach(c => cachedDatasets += c)

            // fire queries to drop the cache
            for(ds <- dropCandidate) {
              hiveContext.hql("UNCACHE TABLE " + ds.getCachedName())
              hiveContext.hql(QueryUtil.getDropTableSQL(ds.getCachedName()))

              manager.postEvent(new DatasetUnloadedFromCache(ds))
            }

            // fire queries to cache columns
            for(ds <- cacheCandidate) {
              var drop_cache_table = QueryUtil.getDropTableSQL(
                  ds.getCachedName())
              var query_create = QueryUtil.getCreateTableAsCachedSQL(ds)
              println("running queries")
              println(drop_cache_table)
              println(query_create)
              hiveContext.hql(drop_cache_table)
              try {
            	  hiveContext.hql(query_create)
              } catch{
                case e: Exception => 
                println("not able to create table. "); e.printStackTrace()
                }
              hiveContext.hql("CACHE TABLE " + ds.getCachedName())	// not cached at this stage since spark evaluates lazily

              manager.postEvent(new DatasetLoadedToCache(ds))
            }
            
            // fire sql queries
            for(query <- batch) {
              var queryString: String = ""
              var cacheUsed: Double = 0
              if(cachedDatasets.contains(query.getDataset())) {
                println("use cache table: " + query.getDataset())
                queryString = query.toHiveQL(true)
                cacheUsed = query.getScanBenefit()
              } else {
                println("use external table: " + query.getDataset())
                queryString = query.toHiveQL(false)
              }
              println("query fired: " + queryString)
              manager.postEvent(new QueryPushedToSparkScheduler(query, 
                  cacheUsed))
              sc.setJobGroup(query.getQueueID().toString(), queryString)
              sc.setLocalProperty("spark.scheduler.pool", 
                  query.getQueueID().toString())
              val result = hiveContext.hql(queryString)
              result.collect()
            }

      }
      
      override def run() {
        while (true) {
          println("single ds cacheplanner invoked")
          if (!started) {
            // before returning schedule remaining queries
            setup.run() //processBatch(fetchNextBatch)
            println("returning because stopped!")
            return
          }

          try { 
        	  Thread.sleep(batchTime * 1000)
          } catch {
            case e:InterruptedException => e.printStackTrace
          }

          setup.run()

        }
      }
    }
  }
}
