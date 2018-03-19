/*! ******************************************************************************
 *
 * Pentaho Data Integration
 *
 * Copyright (C) 2018 by Hitachi Vantara : http://www.pentaho.com
 *
 *******************************************************************************
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 ******************************************************************************/

package org.pentaho.di.trans.dataservice.streaming.execution;

import com.google.common.base.Throwables;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import io.reactivex.Observable;
import org.pentaho.di.core.RowMetaAndData;
import org.pentaho.di.core.exception.KettleException;
import org.pentaho.di.core.exception.KettleStepException;
import org.pentaho.di.core.exception.KettleValueException;
import org.pentaho.di.core.logging.LogChannelInterface;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.trans.Trans;
import org.pentaho.di.trans.TransAdapter;
import org.pentaho.di.trans.dataservice.streaming.StreamList;
import org.pentaho.di.trans.dataservice.utils.DataServiceConstants;
import org.pentaho.di.trans.step.RowAdapter;
import org.pentaho.di.trans.step.StepInterface;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * This class represents a streaming execution for a service transformation.
 * It spans a thread to run the transformation when data is requested and running thread exists, caching the requests.
 * When all the cached requests are expired the transformation is stopped and it's associated thread terminated.
 */
public class StreamingServiceTransExecutor {
  private final Trans serviceTrans;
  private final String id;
  private final String serviceStepName;
  private final AtomicBoolean isRunning = new AtomicBoolean( false );

  private StreamList<RowMetaAndData> stepStream;
  private long lastCacheCleanupMillis = 0;
  private int windowMaxRowLimit;
  private long windowMaxTimeLimit;

  /**
   * Service listener cache.
   */
  private final Cache<String, StreamExecutionListener> serviceListeners = CacheBuilder.newBuilder()
    .expireAfterAccess( DataServiceConstants.STREAMING_CACHE_DURATION, DataServiceConstants.STREAMING_CACHE_TIME_UNIT )
    .removalListener( new RemovalListener<String, StreamExecutionListener>() {
      public void onRemoval( RemovalNotification<String, StreamExecutionListener> removal ) {
        LogChannelInterface log = serviceTrans.getLogChannel();

        removal.getValue().unSubscribe();

        log.logDebug( DataServiceConstants.STREAMING_CACHE_REMOVED + removal.getKey() );
      }
    } )
    .softValues()
    .build();

  /**
   * Constructor.
   *
   * @param id The instance id.
   * @param serviceTrans The {@link org.pentaho.di.trans.Trans} Service Transformation.
   * @param serviceStepName The Service Transformation Step Name.
   * @param windowMaxRowLimit The streaming window max row limit.
   * @param windowMaxTimeLimit The streaming window max time limit.
   */
  public StreamingServiceTransExecutor( final String id, final Trans serviceTrans, final String serviceStepName,
                                        final int windowMaxRowLimit, final long windowMaxTimeLimit ) {
    this.serviceTrans = serviceTrans;
    this.id = id;
    this.serviceStepName = serviceStepName;
    this.windowMaxRowLimit = windowMaxRowLimit;
    this.windowMaxTimeLimit = windowMaxTimeLimit;

    // Add trans finished listener to reset the isRunning control boolean
    this.serviceTrans.addTransListener( new TransAdapter() {
      @Override
      public void transFinished( Trans trans ) {
        isRunning.set( false );
      }
    } );
  }

  /**
   * Getter for the {@link org.pentaho.di.trans.Trans} serviceTransformation
   *
   * @return {@link org.pentaho.di.trans.Trans} serviceTransformation
   */
  public Trans getServiceTrans() {
    return serviceTrans;
  }

  /**
   * Getter for the id.
   *
   * @return the id of this object instance.
   */
  public String getId() {
    return id;
  }

  /**
   * Getter for the window max row limit.
   *
   * @return the window max row limit of this object instance.
   */
  public long getWindowMaxRowLimit() {
    return windowMaxRowLimit;
  }

  /**
   * Getter for the window max time limit.
   *
   * @return the window max time limit of this object instance.
   */
  public long getWindowMaxTimeLimit() {
    return windowMaxTimeLimit;
  }

  /**
   * This method is used by the client to get the stream listener fot the given query and window parameters.
   * If no cached listener exists it creates a new one, and spans the Service Transformation execution thread if not
   * started, otherwise it returns the cached listener.
   *
   * @param query The requested query.
   * @param windowRowSize The requested query window size. ( >= 0 )
   * @param windowMillisSize The requested query window size time based. ( >= 0 )
   * @param windowRate The requested query window update rate - Number of rows for size based windows and milliseconds
   *                   for time based windows. ( >= 0 )
   * @return The {@link StreamExecutionListener} for the given query or null if parameters invalid.
   */
  public StreamExecutionListener getBuffer( String query, int windowRowSize, long windowMillisSize, long windowRate ) {
    windowRowSize = windowRowSize <= 0 ? 0 : Math.min( windowRowSize, windowMaxRowLimit );
    windowMillisSize = windowMillisSize <= 0 ? 0 : Math.min( windowMillisSize, windowMaxTimeLimit );
    windowRate = windowRate <= 0 ? 0
      : ( windowMillisSize > 0 ? Math.min( windowRate, windowMaxTimeLimit ) : Math.min( windowRate, windowMaxRowLimit ) );

    if ( windowRowSize == 0 && windowMillisSize == 0 ) {
      return null;
    }

    String cacheId = getCacheKey( query, windowRowSize, windowMillisSize, windowRate, windowMaxRowLimit,
      windowMaxTimeLimit );

    StreamExecutionListener streamListener = serviceListeners.getIfPresent( cacheId );

    if ( streamListener == null ) {
      if ( stepStream == null ) {
        stepStream = new StreamList<>();
      }

      Observable<List<RowMetaAndData>> buffer = null;
      Observable<List<RowMetaAndData>> fallbackBuffer = null;

      if ( windowRate > 0 ) {
        if ( windowMillisSize > 0 && windowRowSize > 0 ) {
          buffer = stepStream.getStream().buffer( windowMillisSize, TimeUnit.MILLISECONDS );
          fallbackBuffer = stepStream.getStream().buffer( windowRowSize );
        } else if ( windowMillisSize > 0 ) {
          buffer = stepStream.getStream().buffer( windowMillisSize, windowRate, TimeUnit.MILLISECONDS );
          fallbackBuffer = stepStream.getStream().buffer( windowMaxRowLimit );
        } else if ( windowRowSize > 0 ) {
          buffer = stepStream.getStream().buffer( windowRowSize, (int) windowRate );
          fallbackBuffer = stepStream.getStream()
            .buffer( windowMaxTimeLimit, TimeUnit.MILLISECONDS );
        }
      } else {
        windowRowSize = windowRowSize > 0 ? windowRowSize : windowMaxRowLimit;
        windowMillisSize = windowMillisSize > 0 ? windowMillisSize : windowMaxTimeLimit;

        buffer = stepStream.getStream().buffer( windowRowSize );
        fallbackBuffer = stepStream.getStream().buffer( windowMillisSize, TimeUnit.MILLISECONDS );
      }

      streamListener = new StreamExecutionListener( buffer, fallbackBuffer );

      serviceListeners.put( cacheId, streamListener );
    }

    if ( isRunning.compareAndSet( false, true  ) ) {
      startService();
    }

    return streamListener;
  }

  /**
   * Generates the cache key for a given query with a specific size and rate.
   *
   * @param query The query.
   * @param size The query window size.
   * @param millis The query window time.
   * @param rate The query window rate.
   * @param maxRows The query window max rows.
   * @param maxTime The query window max time.
   * @return The cache key for the query.
   */
  private String getCacheKey( String query, int size, long millis, long rate, int maxRows, long maxTime ) {
    return query.concat( String.valueOf( size ) ).concat( "-" ).concat( String.valueOf( millis ) )
      .concat( "-" ).concat( String.valueOf( rate ) )
      .concat( "-" ).concat( String.valueOf( maxRows ) )
      .concat( "-" ).concat( String.valueOf( maxTime ) );
  }

  /**
   * Starts the Service transformation and its row event listener.
   */
  private void startService() {
    try {
      lastCacheCleanupMillis = System.currentTimeMillis();
      serviceTrans.startThreads();

      StepInterface serviceStep = serviceTrans.findRunThread( serviceStepName );
      if ( serviceStep == null ) {
        throw Throwables.propagate( new KettleException( "Service step is not accessible" ) );
      }
      serviceStep.addRowListener( new RowAdapter() {
        /**
         * Listener for the service transformation output rows.
         * If the stepStream has any valid registered listeners it writes the output row to the stepStream,
         * otherwise stops the service transformation and kills its running thread.
         *
         * @param rowMeta The metadata of the written row.
         * @param row The data of the written row.
         * @throws KettleStepException
         */
        @Override
        public void rowWrittenEvent( RowMetaInterface rowMeta, Object[] row ) throws KettleStepException {
          // Simply pass along the row to the other transformation (to the Injector step)
          LogChannelInterface log = serviceTrans.getLogChannel();

          try {
            if ( log.isRowLevel() ) {
              log.logRowlevel( DataServiceConstants.PASSING_ALONG_ROW + rowMeta.getString( row ) );
            }
          } catch ( KettleValueException e ) {
            // Ignore error
          }

          serviceCacheCleanup();

          if ( serviceListeners.size() > 0 ) {
            RowMetaAndData rowData = new RowMetaAndData();
            rowData.setRowMeta( rowMeta );
            rowData.setData( row );

            stepStream.add( rowData );
          } else if ( isRunning.compareAndSet( true, false ) ) {
            serviceTrans.stopAll();
            serviceTrans.waitUntilFinished();
            stepStream = null;
            log.logDetailed( DataServiceConstants.STREAMING_TRANSFORMATION_STOPPED );
          }
        }
      } );
    } catch ( KettleException e ) {
      throw Throwables.propagate( e );
    }
  }

  /**
   * Clean up service listener cache.
   */
  private void serviceCacheCleanup() {
    long currentTime = System.currentTimeMillis();
    long updateTime = lastCacheCleanupMillis + ( DataServiceConstants.STREAMING_CACHE_CLEANUP_INTERVAL_SECONDS * 1000 );

    if ( updateTime <= currentTime ) {
      serviceListeners.cleanUp();
      lastCacheCleanupMillis = currentTime;
    }
  }

  /**
   * Clears the listeners cache.
   */
  public void clearCache() {
    serviceListeners.invalidateAll();
    serviceListeners.cleanUp();
  }
}
