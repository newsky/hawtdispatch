/**
 * Copyright (C) 2012 FuseSource, Inc.
 * http://fusesource.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.fusesource.hawtdispatch.transport;

import org.fusesource.hawtdispatch.Task;

import javax.net.ssl.*;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;

import static javax.net.ssl.SSLEngineResult.HandshakeStatus.NEED_UNWRAP;
import static javax.net.ssl.SSLEngineResult.HandshakeStatus.NEED_WRAP;
import static javax.net.ssl.SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING;
import static javax.net.ssl.SSLEngineResult.Status.BUFFER_OVERFLOW;

/**
 * An SSL Transport for secure communications.
 *
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 */
public class SslTransport extends TcpTransport implements SecuredSession {


    /**
     * Maps uri schemes to a protocol algorithm names.
     * Valid algorithm names listed at:
     * http://download.oracle.com/javase/6/docs/technotes/guides/security/StandardNames.html#SSLContext
     */
    public static String protocol(String scheme) {
        if( scheme.equals("tls") ) {
            return "TLS";
        } else if( scheme.startsWith("tlsv") ) {
            return "TLSv"+scheme.substring(4);
        } else if( scheme.equals("ssl") ) {
            return "SSL";
        } else if( scheme.startsWith("sslv") ) {
            return "SSLv"+scheme.substring(4);
        }
        return null;
    }

    enum ClientAuth {
        WANT, NEED, NONE
    };

    private ClientAuth clientAuth = ClientAuth.WANT;

    private SSLContext sslContext;
    private SSLEngine engine;

    private ByteBuffer readBuffer;
    private boolean readUnderflow;

    private ByteBuffer writeBuffer;
    private boolean writeFlushing;

    private ByteBuffer readOverflowBuffer;
    private SSLChannel ssl_channel = new SSLChannel();


    public void setSSLContext(SSLContext ctx) {
        this.sslContext = ctx;
    }

    /**
     * Allows subclasses of TcpTransportFactory to create custom instances of
     * TcpTransport.
     */
    public static SslTransport createTransport(URI uri) throws Exception {
        String protocol = protocol(uri.getScheme());
        if( protocol !=null ) {
            SslTransport rc = new SslTransport();
            rc.setSSLContext(SSLContext.getInstance(protocol));
            return rc;
        }
        return null;
    }

    public class SSLChannel implements ScatteringByteChannel, GatheringByteChannel {

        public int write(ByteBuffer plain) throws IOException {
            return secure_write(plain);
        }

        public int read(ByteBuffer plain) throws IOException {
            return secure_read(plain);
        }

        public boolean isOpen() {
            return getSocketChannel().isOpen();
        }

        public void close() throws IOException {
            getSocketChannel().close();
        }

        public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
            if(offset+length > srcs.length || length<0 || offset<0) {
                throw new IndexOutOfBoundsException();
            }
            long rc=0;
            for (int i = 0; i < length; i++) {
                ByteBuffer src = srcs[offset+i];
                if(src.hasRemaining()) {
                    rc += write(src);
                }
                if( src.hasRemaining() ) {
                    return rc;
                }
            }
            return rc;
        }

        public long write(ByteBuffer[] srcs) throws IOException {
            return write(srcs, 0, srcs.length);
        }

        public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
            if(offset+length > dsts.length || length<0 || offset<0) {
                throw new IndexOutOfBoundsException();
            }
            long rc=0;
            for (int i = 0; i < length; i++) {
                ByteBuffer dst = dsts[offset+i];
                if(dst.hasRemaining()) {
                    rc += read(dst);
                }
                if( dst.hasRemaining() ) {
                    return rc;
                }
            }
            return rc;
        }

        public long read(ByteBuffer[] dsts) throws IOException {
            return read(dsts, 0, dsts.length);
        }
        
        public Socket socket() {
            SocketChannel c = channel;
            if( c == null ) {
                return null;
            }
            return c.socket();
        }
    }

    public SSLSession getSSLSession() {
        return engine==null ? null : engine.getSession();
    }

    public X509Certificate[] getPeerX509Certificates() {
    	if( engine==null ) {
            return null;
        }
        try {
            ArrayList<X509Certificate> rc = new ArrayList<X509Certificate>();
            for( Certificate c:engine.getSession().getPeerCertificates() ) {
                if(c instanceof X509Certificate) {
                    rc.add((X509Certificate) c);
                }
            }
            return rc.toArray(new X509Certificate[rc.size()]);
        } catch (SSLPeerUnverifiedException e) {
            return null;
        }
    }

    @Override
    public void connecting(URI remoteLocation, URI localLocation) throws Exception {
        assert engine == null;
        engine = sslContext.createSSLEngine();
        engine.setUseClientMode(true);
        super.connecting(remoteLocation, localLocation);
    }

    @Override
    public void connected(SocketChannel channel) throws Exception {
        if (engine == null) {
            engine = sslContext.createSSLEngine();
            engine.setUseClientMode(false);
            switch (clientAuth) {
                case WANT: engine.setWantClientAuth(true); break;
                case NEED: engine.setNeedClientAuth(true); break;
                case NONE: engine.setWantClientAuth(false); break;
            }

        }
        super.connected(channel);
    }

    @Override
    protected void initializeChannel() throws Exception {
        super.initializeChannel();
        SSLSession session = engine.getSession();
        readBuffer = ByteBuffer.allocateDirect(session.getPacketBufferSize());
        readBuffer.flip();
        writeBuffer = ByteBuffer.allocateDirect(session.getPacketBufferSize());
    }

    @Override
    protected void onConnected() throws IOException {
        super.onConnected();
        engine.beginHandshake();
        handshake();
    }

    @Override
    public void flush() {
        if ( engine.getHandshakeStatus()!=NOT_HANDSHAKING ) {
            handshake();
        } else {
            super.flush();
        }
    }

    @Override
    public void drainInbound() {
        if ( engine.getHandshakeStatus()!=NOT_HANDSHAKING ) {
            handshake();
        } else {
            super.drainInbound();
        }
    }

    /**
     * @return true if fully flushed.
     * @throws IOException
     */
    protected boolean transportFlush() throws IOException {
        while (true) {
            if(writeFlushing) {
                int count = super.getWriteChannel().write(writeBuffer);
                if( !writeBuffer.hasRemaining() ) {
                    writeBuffer.clear();
                    writeFlushing = false;
                    suspendWrite();
                    return true;
                } else {
                    return false;
                }
            } else {
                if( writeBuffer.position()!=0 ) {
                    writeBuffer.flip();
                    writeFlushing = true;
                    resumeWrite();
                } else {
                    return true;
                }
            }
        }
    }

    private int secure_write(ByteBuffer plain) throws IOException {
        if( !transportFlush() ) {
            // can't write anymore until the write_secured_buffer gets fully flushed out..
            return 0;
        }
        int rc = 0;
        while ( plain.hasRemaining() ^ engine.getHandshakeStatus()==NEED_WRAP ) {
            SSLEngineResult result = engine.wrap(plain, writeBuffer);
            assert result.getStatus()!= BUFFER_OVERFLOW;
            rc += result.bytesConsumed();
            if( !transportFlush() ) {
                break;
            }
        }
        if( plain.remaining()==0 && engine.getHandshakeStatus()!=NOT_HANDSHAKING ) {
            dispatchQueue.execute(new Task() {
                public void run() {
                    handshake();
                }
            });
        }
        return rc;
    }

    private int secure_read(ByteBuffer plain) throws IOException {
        int rc=0;
        while ( plain.hasRemaining() ^ engine.getHandshakeStatus() == NEED_UNWRAP ) {
            if( readOverflowBuffer !=null ) {
                if(  plain.hasRemaining() ) {
                    // lets drain the overflow buffer before trying to suck down anymore
                    // network bytes.
                    int size = Math.min(plain.remaining(), readOverflowBuffer.remaining());
                    plain.put(readOverflowBuffer.array(), readOverflowBuffer.position(), size);
                    readOverflowBuffer.position(readOverflowBuffer.position()+size);
                    if( !readOverflowBuffer.hasRemaining() ) {
                        readOverflowBuffer = null;
                    }
                    rc += size;
                } else {
                    return rc;
                }
            } else if( readUnderflow ) {
                int count = super.getReadChannel().read(readBuffer);
                if( count == -1 ) {  // peer closed socket.
                    if (rc==0) {
                        return -1;
                    } else {
                        return rc;
                    }
                }
                if( count==0 ) {  // no data available right now.
                    return rc;
                }
                // read in some more data, perhaps now we can unwrap.
                readUnderflow = false;
                readBuffer.flip();
            } else {
                SSLEngineResult result = engine.unwrap(readBuffer, plain);
                rc += result.bytesProduced();
                if( result.getStatus() == BUFFER_OVERFLOW ) {
                    readOverflowBuffer = ByteBuffer.allocate(engine.getSession().getApplicationBufferSize());
                    result = engine.unwrap(readBuffer, readOverflowBuffer);
                    if( readOverflowBuffer.position()==0 ) {
                        readOverflowBuffer = null;
                    } else {
                        readOverflowBuffer.flip();
                    }
                }
                switch( result.getStatus() ) {
                    case CLOSED:
                        if (rc==0) {
                            engine.closeInbound();
                            return -1;
                        } else {
                            return rc;
                        }
                    case OK:
                        if ( engine.getHandshakeStatus()!=NOT_HANDSHAKING ) {
                            dispatchQueue.execute(new Task() {
                                public void run() {
                                    handshake();
                                }
                            });
                        }
                        break;
                    case BUFFER_UNDERFLOW:
                        readBuffer.compact();
                        readUnderflow = true;
                        break;
                    case BUFFER_OVERFLOW:
                        throw new AssertionError("Unexpected case.");
                }
            }
        }
        return rc;
    }

    public void handshake() {
        try {
            if( !transportFlush() ) {
                return;
            }
            switch (engine.getHandshakeStatus()) {
                case NEED_TASK:
                    final Runnable task = engine.getDelegatedTask();
                    if( task!=null ) {
                        blockingExecutor.execute(new Task() {
                            public void run() {
                                task.run();
                                dispatchQueue.execute(new Task() {
                                    public void run() {
                                        if (isConnected()) {
                                            handshake();
                                        }
                                    }
                                });
                            }
                        });
                    }
                    break;

                case NEED_WRAP:
                    secure_write(ByteBuffer.allocate(0));
                    break;

                case NEED_UNWRAP:
                    if( secure_read(ByteBuffer.allocate(0)) == -1) {
                        throw new EOFException("Peer disconnected during ssl handshake");
                    }
                    break;

                case FINISHED:
                case NOT_HANDSHAKING:
                    drainOutboundSource.merge(1);
                    drainInbound();
                    break;

                default:
                    System.err.println("Unexpected ssl engine handshake status: "+ engine.getHandshakeStatus());
                    break;
            }
        } catch (IOException e ) {
            onTransportFailure(e);
        }
    }


    public ReadableByteChannel getReadChannel() {
        return ssl_channel;
    }

    public WritableByteChannel getWriteChannel() {
        return ssl_channel;
    }

    public String getClientAuth() {
        return clientAuth.name();
    }

    public void setClientAuth(String clientAuth) {
        this.clientAuth = ClientAuth.valueOf(clientAuth.toUpperCase());
    }
}


