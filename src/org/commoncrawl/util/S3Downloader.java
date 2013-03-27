/**
 * Copyright 2008 - CommonCrawl Foundation
 * 
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 **/

package org.commoncrawl.util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.Vector;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.util.StringUtils;
import org.commoncrawl.async.EventLoop;
import org.commoncrawl.async.Timer;
import org.commoncrawl.io.NIOBufferList;
import org.commoncrawl.io.NIOHttpConnection;
import org.commoncrawl.io.NIOHttpConnection.State;
import org.commoncrawl.io.NIOHttpHeaders;


/**
 * 
 * @author rana
 *
 */
public class S3Downloader implements NIOHttpConnection.Listener {

  private static final Log LOG = LogFactory.getLog(S3Downloader.class);
  
  private static final int MAX_FAILURES_PER_ITEM = 5;
  private static final int DEFAULT_MAX_PARALLEL_STREAMS = 20;
  private static final int DEFAULT_MIN_HTTP_BUFFER_SIZE = 32 * 1024;
  private static final int DEFAULT_MAX_HTTP_BUFFER_SIZE = 32 * 1024;
  
  private String _s3BucketName;
  private String _s3AccessId;
  private String _s3SecretKey;
  private LinkedList<S3DownloadItem> _queuedItems   = new LinkedList<S3DownloadItem>();
  private LinkedList<NIOHttpConnection> _activeConnections      = new LinkedList<NIOHttpConnection>();
  private EventLoop _eventLoop = new EventLoop();
  private boolean   _ownsEventLoop = false;
  private S3Utils.CallingFormat _callingFormat = S3Utils.CallingFormat.getSubdomainCallingFormat();
  private Callback _callback; 
  private boolean _freezeDownloads = false;
  private int _lastItemId = 0;
  private int _maxParallelStreams = DEFAULT_MAX_PARALLEL_STREAMS;
  private BandwidthUtils.BandwidthHistory _downloaderStats = new BandwidthUtils.BandwidthHistory();
  private boolean _isRequesterPays = false;
  

  public static interface Callback { 
    public boolean downloadStarting(int itemId,String itemKey,int contentLength);
    public boolean contentAvailable(int itemId,String itemKey,NIOBufferList contentBuffer);
    public void downloadFailed(int itemId,String itemKey,String errorCode);
    public void downloadComplete(int itemId,String itemKey);
  }
  
  public S3Downloader(String s3BucketName,String s3AccessId,String s3SecretKey, boolean isRequesterPays) {
    _s3BucketName = s3BucketName;
    _s3AccessId = s3AccessId;
    _s3SecretKey = s3SecretKey;
    _isRequesterPays = isRequesterPays;
  }
  
  public void setMaxParallelStreams(int maxStreams) { 
    _maxParallelStreams = maxStreams;
  }

  public int getMaxParallelStreams() { 
    return _maxParallelStreams;
  }

  public void initialize(Callback listener)throws IOException { 
    initialize(listener,null);
  }
  public void initialize(Callback listener,EventLoop externalEventLoop) throws IOException { 
    
    if (externalEventLoop == null) { 
      _eventLoop = new EventLoop();
      _ownsEventLoop = true;
    }
    else { 
      _eventLoop = externalEventLoop;
      _ownsEventLoop = false;
    }
    
    if (_callback != null) { 
      throw new RuntimeException("Invalid State - start called on already active downloader");
    }
    if (listener == null) {
      throw new IOException("Null Listener is Invalid");
    }
    _callback = listener;
    
    if (_ownsEventLoop) { 
      _eventLoop.start();
    }
  }
  
  public void shutdown() { 

    if (_callback == null) { 
      throw new RuntimeException("Invalid State - stop called on already inactive downloader");
    }
    
    _freezeDownloads = true;
    
    Thread eventThread = (_ownsEventLoop) ? _eventLoop.getEventThread() : null;
    
    
    _eventLoop.setTimer(new Timer(1,false,new Timer.Callback() {

      // shutdown within the context of the async thread ... 
      public void timerFired(Timer timer) {
        
        // fail any active connections 
        for (NIOHttpConnection connection : _activeConnections) { 
          S3DownloadItem item = (S3DownloadItem) connection.getContext();
          if (item != null) { 
            failDownload(item, NIOHttpConnection.ErrorType.UNKNOWN,connection, false);
          }
        }
        
        
        _activeConnections.clear();
        
        // next, fail all queued items 
        for (S3DownloadItem item : _queuedItems) { 
          failDownload(item, NIOHttpConnection.ErrorType.UNKNOWN,null, false);
        }
        _queuedItems.clear();
        _freezeDownloads = false;
        _callback = null;
        if (_ownsEventLoop) { 
          _eventLoop.stop();
        }
        _eventLoop = null;
        _ownsEventLoop = false;
      }  
    }));
      
    try {
      if (eventThread != null) { 
        eventThread.join();
      }
    } catch (InterruptedException e) {
    }
  }
  
  public void waitForCompletion() { 
    try {
      _eventLoop.getEventThread().join();
    } catch (InterruptedException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
  }
  
  public synchronized  int fetchItem(String itemKey) throws IOException { 
    int itemId = -1;
    synchronized (_queuedItems) { 
      itemId = ++_lastItemId;
      _queuedItems.addLast(new S3DownloadItem(itemKey,itemId));
    }
    _eventLoop.setTimer(new Timer(1,false,new Timer.Callback() {

      public void timerFired(Timer timer) {
        // executes in the context of the event loop thread ... 
        downloadNextItem();
      }
      
     }));
    
    return itemId;
  }
  
  public synchronized  int fetchPartialItem(String itemKey,int rangeStart,int bytesToFetch) throws IOException { 
    int itemId = -1;
    S3DownloadItem downloadItem = new S3DownloadItem(itemKey,itemId);
    
    downloadItem.setLastReadPos(rangeStart);
    downloadItem.setContentLength(downloadItem.getLastReadPos() + bytesToFetch);
    
    synchronized (_queuedItems) { 
      itemId = ++_lastItemId;
      _queuedItems.addLast(downloadItem);
    }
    _eventLoop.setTimer(new Timer(1,false,new Timer.Callback() {

      public void timerFired(Timer timer) {
        // executes in the context of the event loop thread ... 
        downloadNextItem();
      }
      
     }));
    
    return itemId;
  }

  
  private void downloadNextItem() { 
    if (_activeConnections.size() < _maxParallelStreams) { 
      S3DownloadItem item = null;
      synchronized (_queuedItems) {
        if (_queuedItems.size() != 0)
          item = _queuedItems.removeFirst();
      }
      if (item != null)
        downloadItem(item);
    }
  }
  
  private void downloadItem(S3DownloadItem item) {

    NIOHttpConnection connection = null;
    try { 
      // construct the url for the item 
      URL theURL = _callingFormat.getURL(false, S3Utils.DEFAULT_HOST, S3Utils.INSECURE_PORT, _s3BucketName, item.getKey(), null);
      connection = new NIOHttpConnection(theURL,_eventLoop.getSelector(),_eventLoop.getResolver(),null);
      connection.getContentBuffer().setMinBufferSize(DEFAULT_MIN_HTTP_BUFFER_SIZE);
      connection.getContentBuffer().setMaxBufferSize(DEFAULT_MAX_HTTP_BUFFER_SIZE);
      // set up the item as the context for the connection ... 
      connection.setContext(item);
      // we don't want to populate default http headers ... 
      connection.setPopulateDefaultHeaderItems(false);
      // get at headers object
      NIOHttpHeaders headers = connection.getRequestHeaders();
      // populate http request string 
      headers.prepend("GET" + " " + theURL.getFile() +" "  + "HTTP/1.1", null);
      // populate host entry ... 
      if (theURL.getPort() != -1 && theURL.getPort() != 80) { 
        headers.set("Host",theURL.getHost() +":"+String.valueOf(theURL.getPort()));
      }
      else { 
        headers.set("Host",theURL.getHost());
      }
      // create a tree map in parallel (to pass to canonicalization routine for s3 auth)
      Map amazonHeaders = new TreeMap();
      
      // add date ... 
      String theDate = httpDate();
      
      headers.set("Date", theDate);
      // and set it in amazon headers ... 
      addToAmazonHeader("Date", theDate, amazonHeaders);
      // add requester pays if specified ... 
      if (_isRequesterPays) { 
        headers.set("x-amz-request-payer", "requester");
        addToAmazonHeader("x-amz-request-payer", "requester",amazonHeaders);
      }

      String canonicalString =  S3Utils.makeCanonicalString("GET", _s3BucketName, item.getKey(), null,amazonHeaders );
      String encodedCanonical = S3Utils.encode(_s3SecretKey, canonicalString, false);
      
      // add auth string to headers ... 
      headers.set("Authorization","AWS " + _s3AccessId + ":" + encodedCanonical);
      
      // figure out of this is a continuation ... 
      if (item.isContinuation()) { 
        // figure out where to start ...
        String rangeString = "bytes=" + item.getLastReadPos() + "-" + item.getContentLength();
        // set the range header ... 
        headers.set("Range",rangeString);
        // and if etag is valid ... 
        if (item.getLastKnownETag() != null) { 
          headers.set("If-match",item.getLastKnownETag());
        }
      }
      // add cache control pragmas ... 
      headers.set ("Connection", "close");
      headers.set("Cache-Control", "no-cache");
      headers.set("Pragma", "no-cache");
      
      // set up the listener relationship 
      connection.setListener(this);
      // and open the  connection
      connection.open();
      
      // add it to list for tracking purposes 
      _activeConnections.add(connection);
      
    }
    catch (IOException e) { 
      LOG.error(StringUtils.stringifyException(e));
      LOG.error("Failure Opening Connection For Key:" + item.getKey());

      synchronized (_queuedItems) { 
        _queuedItems.addLast(item);
      }
      if (connection != null) { 
        // null out listener 
        connection.setListener(null);
        connection.setContext(null);
        // close connection 
        connection.close();
      }
    }
  }
  
  private void requeueDownloadItem(S3DownloadItem item) {
    synchronized (_queuedItems) {
      _queuedItems.addLast(item);
    }
  }
  
  private void resetConnection(NIOHttpConnection theConnection) {
    theConnection.close();
    theConnection.setListener(null);
    theConnection.setContext(null);
    _activeConnections.remove(theConnection);
  }
  private void failDownload(S3DownloadItem item,NIOHttpConnection.ErrorType errorType,NIOHttpConnection theConnection,boolean potentiallyRetry) {
    
    int resultCode = -1;
    
    if (theConnection != null) {
      
      NIOHttpHeaders headers = theConnection.getResponseHeaders();
      resultCode = NIOHttpConnection.getHttpResponseCode(headers);
      
      if (item != null) { 
        item.incrementFailureCount(errorType,resultCode);
      }
      
      resetConnection(theConnection);
    }
    
    if (item != null) { 
      if (potentiallyRetry && item.isDownloadRecoverable()) { 
        requeueDownloadItem(item);
      }
      else { 
        LOG.error("Download Failed for Item:" + item.getKey());
        if (_callback != null) { 
          _callback.downloadFailed(item.getId(),item.getKey(),"Failure Reason:" + errorType.toString() + " ResultCode:" + resultCode);
        }
      }
    }
    if (!_freezeDownloads) { 
      downloadNextItem();
    }
  }
  
  private void completeDownload(S3DownloadItem item,NIOHttpConnection theConnection) {

    if (item != null) { 
      if (_callback != null) { 
        _callback.downloadComplete(item.getId(),item.getKey());
      }
    }
    resetConnection(theConnection);

    if (!_freezeDownloads) { 
      downloadNextItem();
    }
    
   }
  
  // @Override
  public void HttpConnectionStateChanged(NIOHttpConnection theConnection,State oldState, State state) {
    
    if (oldState == State.PARSING_HEADERS && state == State.RECEIVING_CONTENT) { 
      NIOHttpHeaders headers = theConnection.getResponseHeaders();
      LOG.info("*** S3 INCOMING HEADERS:" + headers.toString());
      LOG.info("Content Length From Header for:" + theConnection.getURL() + " is:" + headers.findValue("Content-Length"));
    }
    
    LOG.info("S3Download Connection:" + theConnection.getURL() +" Old State:" + oldState + " NewState:" + state);
    // get context
    S3DownloadItem item = (S3DownloadItem) theConnection.getContext();

    // if we started receiving content ... 
    if (state == State.RECEIVING_CONTENT) {
      
      // this means we successfully parsed http header ... 
      // pick up etag and content length for the item ... 
      NIOHttpHeaders headers = theConnection.getResponseHeaders();
      
      int resultCode = NIOHttpConnection.getHttpResponseCode(headers);
      
      boolean continueDownloading = false;
      boolean isContinuation = false;
      
      if (item != null) { 
        // if success 
        if (resultCode >= 200 && resultCode <300) { 
          
          continueDownloading = true;
          
          String etagValue = headers.findValue("ETag");
          String contentLengthValue = headers.findValue("Content-Length");
          String rangeValue = headers.findValue("Content-Range");
          
          if(etagValue != null && contentLengthValue != null) { 
            try {
              // now check range value .. in case it is set ... 
              if (rangeValue != null) {
                isContinuation = true;
                // replace the content length value with value embedded in range response ... 
                contentLengthValue = rangeValue.substring(rangeValue.indexOf('/') + 1);
              }
              
              int contentLength = Integer.parseInt(contentLengthValue);
              if (contentLength != -1) {
                
                item.setLastKnownETagAndContentLength(etagValue, contentLength);
              }
            }
            catch (NumberFormatException e) { 
              LOG.error(StringUtils.stringifyException(e));
              continueDownloading = false;
            }
          }
        }
      }
      if (continueDownloading) {
        if (!isContinuation) { 
          if (_callback != null) { 
            continueDownloading = _callback.downloadStarting(item.getId(),item.getKey(), item.getContentLength());
          }
        }
      }
      if (!continueDownloading) { 
        failDownload(item,NIOHttpConnection.ErrorType.UNKNOWN,theConnection,true);
      }
    }
    
    if (state == State.ERROR) {
      failDownload(item, theConnection.getErrorType(), theConnection,true);
    }
    
    if (state == State.DONE) {
      completeDownload(item, theConnection);
    }
  }

  // @Override
  public void HttpContentAvailable(NIOHttpConnection theConnection,NIOBufferList contentBuffer) {

    //LOG.info("Content Available for Connection:" + theConnection.getURL() +" BytesAvailable:" + contentBuffer.available());
    // get context
    S3DownloadItem item = (S3DownloadItem) theConnection.getContext();

    if (item != null)  {
      // update item download stats ... 
      item.getDownloadStats().update(contentBuffer.available());
      // and aggregate stats ... 
      _downloaderStats.update(contentBuffer.available());
      // upgrade the item's content status 
      item.setLastReadPos(item.getLastReadPos() + contentBuffer.available());
      // upgrade stats too ...
      item.incrementDownloadedBytesCounter(contentBuffer.available());
      // figure out if we are going to dump stats ... 
      if (item.getDownloadedBytes() >= 100000) { 
        BandwidthUtils.BandwidthStats stats = new BandwidthUtils.BandwidthStats();
        BandwidthUtils.BandwidthStats aggregateStats = new BandwidthUtils.BandwidthStats();
        
        item.getDownloadStats().calcSpeed(stats);
        _downloaderStats.calcSpeed(aggregateStats);
        // and dump them to the log for now ...
        /*
        LOG.info("Download Speed for Item:" + item.getKey() + " ID:("+ item.getId() +") " + stats.scaledBytesPerSecond + " " + stats.scaledBytesUnits +" " + 
                      aggregateStats.scaledBytesPerSecond + " " + aggregateStats.scaledBytesUnits + "(" + _activeConnections.size() +")");
                      */
        // reset bytes download counter ... 
        item.resetDownloadedBytes();
      }
      
      
      boolean continueDownload = true;
      // callback to listener 
      if (_callback != null) { 
        continueDownload = _callback.contentAvailable(item.getId(),item.getKey(), contentBuffer);
      }
      
      if (!continueDownload) { 
        LOG.info("Explicit Abort Received via contentAvailabe for Item:" + item.getKey());
        failDownload(item, NIOHttpConnection.ErrorType.UNKNOWN, theConnection, false);
      }
    }
    try {
      while (contentBuffer.read() != null) ;
    } catch (IOException e) {
      LOG.error(StringUtils.stringifyException(e));
    }
    // always reset content buffer to preserve memory ... 
    contentBuffer.reset();
    
  }
  
  /**
   * Generate an rfc822 date for use in the Date HTTP header.
   */
  private static String httpDate() {
      final String DateFormat = "EEE, dd MMM yyyy HH:mm:ss ";
      SimpleDateFormat format = new SimpleDateFormat( DateFormat, Locale.US );
      format.setTimeZone( TimeZone.getTimeZone( "GMT" ) );
      return format.format( new Date() ) + "GMT";
  }
 
  private static void addToAmazonHeader(String key,String value,Map amazonHeaders) { 
    List<String> list  = (List<String>) amazonHeaders.get(key);
    if (list == null) { 
      list = new Vector<String>();
      amazonHeaders.put(key, list);
    }
    list.add(value);
  }

  public static void main(String[] args) {
    
    boolean isRequesterPays = args[3].equals("1");
    S3Downloader downloader = new S3Downloader(args[0],args[1],args[2],isRequesterPays);
    String itemToFetch = args[4];
    
    try {
      
      
      downloader.initialize( new Callback() {

        Map<String,FileChannel> channelMap = new HashMap<String,FileChannel>();
        
        public boolean contentAvailable(int itemId,String itemKey, NIOBufferList contentBuffer) {
          LOG.info("Key:" + itemKey + " GOT:" + contentBuffer.available() + " Bytes");
          
          FileChannel channel  = channelMap.get(itemKey);
          if (channel != null) { 
            try { 
              while (contentBuffer.available() != 0) { 
                ByteBuffer buffer = contentBuffer.read();
                channel.write(buffer);  
              }
              return true;
            }
            catch (IOException e) { 
              LOG.error(StringUtils.stringifyException(e));
              return false;
            }
          }
          return false;
        }

        public void downloadComplete(int itemId,String itemKey) {
          LOG.info("Key:" + itemKey + " DownloadComplete");
          FileChannel channel  = channelMap.get(itemKey);
          if (channel != null) { 
            try {
              channel.close();
            } catch (IOException e) {
              LOG.error(StringUtils.stringifyException(e));
            }
          }
          channelMap.remove(itemKey);
        }

        public void downloadFailed(int itemId,String itemKey, String errorCode) {
          LOG.info("Key:" + itemKey + " DownloadFailed. ErrorCode:" + errorCode);
          FileChannel channel  = channelMap.get(itemKey);
          if (channel != null) { 
            try {
              channel.close();
            } catch (IOException e) {
              LOG.error(StringUtils.stringifyException(e));
            }
          }
          channelMap.remove(itemKey);
        }

        public boolean downloadStarting(int itemId,String itemKey, int contentLength) {
          LOG.info("Key:" + itemKey + " DownloadStarting - ContentLength:" + contentLength);
          File file = new File("/tmp/" + itemKey);
          if (file.exists())
            file.delete();
          file.getParentFile().mkdirs();
          FileOutputStream fileHandle = null;
          try {
            fileHandle = new FileOutputStream(file);
            //LOG.info("Key:" + itemKey + " Created File:" + file.getAbsolutePath());
            channelMap.put(itemKey, fileHandle.getChannel());
          } catch (IOException e) {
            LOG.error(StringUtils.stringifyException(e));
            if (fileHandle != null)
              try {
                fileHandle.close();
              } catch (IOException e1) {
              }
            return false;
          }

          return true;
        }
      });
      
      downloader.fetchItem(itemToFetch);
      
      downloader.waitForCompletion();
    } catch (IOException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
    
    
  }
  
  private static class S3DownloadItem { 
    
    public S3DownloadItem(String itemKey,int itemId) { 
      _itemKey  = itemKey;
      _itemId = itemId;
    }

    public void downloadComplete() { 
      //LOG.info("Download Complete for Item:" + _itemKey);
    }
    
    public void downloadFailed() { 
      //LOG.info("Download Failed for Item:" + _itemKey);
    }
    
    public String getKey() { return _itemKey; }
    public int     getId() {  return _itemId; }
    
    public void setLastReadPos(int lastReadPos) { 
      _lastReadPos= lastReadPos;
    }
    public int   getLastReadPos() { 
      return _lastReadPos;
    }
    
    public int getFailureCount() { 
      return _failureCount;
    }
    
    public BandwidthUtils.BandwidthHistory getDownloadStats() { 
      return _downloadStats;
    }

    
    public boolean isDownloadRecoverable() { 
      // we can recover from io exception or timeout type errors 
      if (_lastErrorType == NIOHttpConnection.ErrorType.UNKNOWN || _lastErrorType == NIOHttpConnection.ErrorType.IOEXCEPTION || _lastErrorType == NIOHttpConnection.ErrorType.TIMEOUT) { 
        // all 400 error codes are unrecoverable failures
        if (_lastKnownResultCode >= 400 && _lastKnownResultCode < 500) { 
          return false;
        }
        return _failureCount < MAX_FAILURES_PER_ITEM;
      }
      // all other type of connection errors are unrecoverable ...
      return false;
    }
    
    public void incrementFailureCount(NIOHttpConnection.ErrorType errorType,int lastKnownResultCode) { 
      _failureCount++;
      _lastErrorType = errorType;
      _lastKnownResultCode = lastKnownResultCode;
    }
    
    public String getLastKnownETag() { 
      return _lastKnownETag;
    }
    
    public int getContentLength() { 
      return _lastKnownContentLength;
    }
    
    public void setContentLength(int contentLength) { 
      _lastKnownContentLength = contentLength;
    }

    public boolean isContinuation() { 
      return (_lastReadPos != 0);
    }    
    
    public int getDownloadedBytes() { 
      return _downloadedBytes;
    }
    
    public void resetDownloadedBytes() { 
      _downloadedBytes = 0;
    }
    
    public void incrementDownloadedBytesCounter(int newlyReceivedByteCount) { 
      _downloadedBytes += newlyReceivedByteCount;
    }
    
    public void setLastKnownETagAndContentLength(String etag,int contentLength) { 
      _lastKnownETag = etag;
      _lastKnownContentLength = contentLength;
    }
    
    private String      _itemKey;
    private int         _itemId =0;
    private String      _lastKnownETag = null;
    private int          _lastKnownContentLength = -1;
    private short      _failureCount = 0;
    private int          _lastReadPos = 0;
    private NIOHttpConnection.ErrorType _lastErrorType=NIOHttpConnection.ErrorType.UNKNOWN;
    private int          _lastKnownResultCode=-1;
    private int          _downloadedBytes;
    private BandwidthUtils.BandwidthHistory     _downloadStats = new BandwidthUtils.BandwidthHistory();
  }
  
}
