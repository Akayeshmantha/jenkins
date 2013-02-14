/*
 * The MIT License
 * 
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi, Stephen Connolly
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.slaves;

import hudson.TcpSlaveAgentListener;
import hudson.model.*;
import hudson.util.IOException2;
import hudson.util.IOUtils;
import hudson.util.io.ReopenableRotatingFileOutputStream;
import jenkins.model.Jenkins.MasterComputer;
import hudson.remoting.Channel;
import hudson.remoting.VirtualChannel;
import hudson.remoting.Callable;
import hudson.util.StreamTaskListener;
import hudson.util.NullStream;
import hudson.util.RingBufferLogHandler;
import hudson.util.Futures;
import hudson.FilePath;
import hudson.lifecycle.WindowsSlaveInstaller;
import hudson.Util;
import hudson.AbortException;
import hudson.remoting.Launcher;
import static hudson.slaves.SlaveComputer.LogHolder.SLAVE_LOG_HANDLER;
import hudson.slaves.OfflineCause.ChannelTermination;
import hudson.util.Secret;

import java.io.File;
import java.io.OutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.security.SecureRandom;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.Handler;
import java.util.List;
import java.util.Collections;
import java.util.ArrayList;
import java.nio.charset.Charset;
import java.util.concurrent.Future;
import java.security.Security;

import hudson.util.io.ReopenableFileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.security.GeneralSecurityException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.servlet.RequestDispatcher;
import jenkins.model.Jenkins;
import jenkins.slaves.JnlpSlaveAgentProtocol;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpRedirect;

import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import org.kohsuke.stapler.ResponseImpl;
import org.kohsuke.stapler.WebMethod;
import org.kohsuke.stapler.compression.FilterServletOutputStream;

/**
 * {@link Computer} for {@link Slave}s.
 *
 * @author Kohsuke Kawaguchi
 */
public class SlaveComputer extends Computer {
    private volatile Channel channel;
    private volatile transient boolean acceptingTasks = true;
    private Charset defaultCharset;
    private Boolean isUnix;
    /**
     * Effective {@link ComputerLauncher} that hides the details of
     * how we launch a slave agent on this computer.
     *
     * <p>
     * This is normally the same as {@link Slave#getLauncher()} but
     * can be different. See {@link #grabLauncher(Node)}. 
     */
    private ComputerLauncher launcher;

    /**
     * Perpetually writable log file.
     */
    private final ReopenableFileOutputStream log;

    /**
     * {@link StreamTaskListener} that wraps {@link #log}, hence perpetually writable.
     */
    private final TaskListener taskListener;


    /**
     * Number of failed attempts to reconnect to this node
     * (so that if we keep failing to reconnect, we can stop
     * trying.)
     */
    private transient int numRetryAttempt;

    /**
     * Tracks the status of the last launch operation, which is always asynchronous.
     * This can be used to wait for the completion, or cancel the launch activity.
     */
    private volatile Future<?> lastConnectActivity = null;

    private Object constructed = new Object();

    public SlaveComputer(Slave slave) {
        super(slave);
        this.log = new ReopenableRotatingFileOutputStream(getLogFile(),10);
        this.taskListener = new StreamTaskListener(log);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isAcceptingTasks() {
        return acceptingTasks;
    }

    /**
     * @since 1.498
     */
    public String getJnlpMac() {
        return JnlpSlaveAgentProtocol.SLAVE_SECRET.mac(getName());
    }

    /**
     * Allows a {@linkplain hudson.slaves.ComputerLauncher} or a {@linkplain hudson.slaves.RetentionStrategy} to
     * suspend tasks being accepted by the slave computer.
     *
     * @param acceptingTasks {@code true} if the slave can accept tasks.
     */
    public void setAcceptingTasks(boolean acceptingTasks) {
        this.acceptingTasks = acceptingTasks;
    }

    /**
     * True if this computer is a Unix machine (as opposed to Windows machine).
     *
     * @return
     *      null if the computer is disconnected and therefore we don't know whether it is Unix or not.
     */
    public Boolean isUnix() {
        return isUnix;
    }

    @Override
    public Slave getNode() {
        return (Slave)super.getNode();
    }

    @Override
    public String getIcon() {
        Future<?> l = lastConnectActivity;
        if(l!=null && !l.isDone())
            return "computer-flash.gif";
        return super.getIcon();
    }

    /**
     * @deprecated since 2008-05-20.
     */
    @Deprecated @Override
    public boolean isJnlpAgent() {
        return launcher instanceof JNLPLauncher;
    }

    @Override
    public boolean isLaunchSupported() {
        return launcher.isLaunchSupported();
    }

    public ComputerLauncher getLauncher() {
        return launcher;
    }

    protected Future<?> _connect(boolean forceReconnect) {
        if(channel!=null)   return Futures.precomputed(null);
        if(!forceReconnect && isConnecting())
            return lastConnectActivity;
        if(forceReconnect && isConnecting())
            logger.fine("Forcing a reconnect on "+getName());

        closeChannel();
        return lastConnectActivity = Computer.threadPoolForRemoting.submit(new java.util.concurrent.Callable<Object>() {
            public Object call() throws Exception {
                // do this on another thread so that the lengthy launch operation
                // (which is typical) won't block UI thread.
                try {
                    log.rewind();
                    try {
                        for (ComputerListener cl : ComputerListener.all())
                            cl.preLaunch(SlaveComputer.this, taskListener);

                        launcher.launch(SlaveComputer.this, taskListener);
                    } catch (AbortException e) {
                        taskListener.error(e.getMessage());
                        throw e;
                    } catch (IOException e) {
                        Util.displayIOException(e,taskListener);
                        e.printStackTrace(taskListener.error(Messages.ComputerLauncher_unexpectedError()));
                        throw e;
                    } catch (InterruptedException e) {
                        e.printStackTrace(taskListener.error(Messages.ComputerLauncher_abortedLaunch()));
                        throw e;
                    }
                } finally {
                    if (channel==null) {
                        offlineCause = new OfflineCause.LaunchFailed();
                        for (ComputerListener cl : ComputerListener.all())
                            cl.onLaunchFailure(SlaveComputer.this, taskListener);
                    }
                }

                if (channel==null)
                    throw new IOException("Slave failed to connect, even though the launcher didn't report it. See the log output for details.");
                return null;
            }
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void taskAccepted(Executor executor, Queue.Task task) {
        super.taskAccepted(executor, task);
        if (launcher instanceof ExecutorListener) {
            ((ExecutorListener)launcher).taskAccepted(executor, task);
        }
        
        //getNode() can return null at indeterminate times when nodes go offline
        Slave node = getNode();
        if (node != null && node.getRetentionStrategy() instanceof ExecutorListener) {
            ((ExecutorListener)node.getRetentionStrategy()).taskAccepted(executor, task);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void taskCompleted(Executor executor, Queue.Task task, long durationMS) {
        super.taskCompleted(executor, task, durationMS);
        if (launcher instanceof ExecutorListener) {
            ((ExecutorListener)launcher).taskCompleted(executor, task, durationMS);
        }
        RetentionStrategy r = getRetentionStrategy();
        if (r instanceof ExecutorListener) {
            ((ExecutorListener) r).taskCompleted(executor, task, durationMS);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void taskCompletedWithProblems(Executor executor, Queue.Task task, long durationMS, Throwable problems) {
        super.taskCompletedWithProblems(executor, task, durationMS, problems);
        if (launcher instanceof ExecutorListener) {
            ((ExecutorListener)launcher).taskCompletedWithProblems(executor, task, durationMS, problems);
        }
        RetentionStrategy r = getRetentionStrategy();
        if (r instanceof ExecutorListener) {
            ((ExecutorListener) r).taskCompletedWithProblems(executor, task, durationMS, problems);
        }
    }

    @Override
    public boolean isConnecting() {
        Future<?> l = lastConnectActivity;
        return isOffline() && l!=null && !l.isDone();
    }

    public OutputStream openLogFile() {
        try {
            log.rewind();
            return log;
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to create log file "+getLogFile(),e);
            return new NullStream();
        }
    }

    private final Object channelLock = new Object();

    public void setChannel(InputStream in, OutputStream out, TaskListener taskListener, Channel.Listener listener) throws IOException, InterruptedException {
        setChannel(in,out,taskListener.getLogger(),listener);
    }

    /**
     * Creates a {@link Channel} from the given stream and sets that to this slave.
     *
     * @param in
     *      Stream connected to the remote "slave.jar". It's the caller's responsibility to do
     *      buffering on this stream, if that's necessary.
     * @param out
     *      Stream connected to the remote peer. It's the caller's responsibility to do
     *      buffering on this stream, if that's necessary.
     * @param launchLog
     *      If non-null, receive the portion of data in <tt>is</tt> before
     *      the data goes into the "binary mode". This is useful
     *      when the established communication channel might include some data that might
     *      be useful for debugging/trouble-shooting.
     * @param listener
     *      Gets a notification when the channel closes, to perform clean up. Can be null.
     *      By the time this method is called, the cause of the termination is reported to the user,
     *      so the implementation of the listener doesn't need to do that again.
     */
    public void setChannel(InputStream in, OutputStream out, OutputStream launchLog, Channel.Listener listener) throws IOException, InterruptedException {
        Channel channel = new Channel(nodeName,threadPoolForRemoting, Channel.Mode.NEGOTIATE, in,out, launchLog);
        setChannel(channel,launchLog,listener);
    }

    /**
     * Sets up the connection through an exsting channel.
     *
     * @since 1.444
     */
    public void setChannel(Channel channel, OutputStream launchLog, Channel.Listener listener) throws IOException, InterruptedException {
        if(this.channel!=null)
            throw new IllegalStateException("Already connected");

        final TaskListener taskListener = new StreamTaskListener(launchLog);
        PrintStream log = taskListener.getLogger();

        channel.addListener(new Channel.Listener() {
            @Override
            public void onClosed(Channel c, IOException cause) {
                // Orderly shutdown will have null exception
                if (cause!=null) {
                    offlineCause = new ChannelTermination(cause);
                    cause.printStackTrace(taskListener.error("Connection terminated"));
                } else {
                    taskListener.getLogger().println("Connection terminated");
                }
                closeChannel();
                launcher.afterDisconnect(SlaveComputer.this, taskListener);
            }
        });
        if(listener!=null)
            channel.addListener(listener);

        String slaveVersion = channel.call(new SlaveVersion());
        log.println("Slave.jar version: " + slaveVersion);

        boolean _isUnix = channel.call(new DetectOS());
        log.println(_isUnix? hudson.model.Messages.Slave_UnixSlave():hudson.model.Messages.Slave_WindowsSlave());

        String defaultCharsetName = channel.call(new DetectDefaultCharset());

        String remoteFs = getNode().getRemoteFS();
        if(_isUnix && !remoteFs.contains("/") && remoteFs.contains("\\"))
            log.println("WARNING: "+remoteFs+" looks suspiciously like Windows path. Maybe you meant "+remoteFs.replace('\\','/')+"?");
        FilePath root = new FilePath(channel,getNode().getRemoteFS());

        // reference counting problem is known to happen, such as JENKINS-9017, and so as a preventive measure
        // we pin the base classloader so that it'll never get GCed. When this classloader gets released,
        // it'll have a catastrophic impact on the communication.
        channel.pinClassLoader(getClass().getClassLoader());

        channel.call(new SlaveInitializer());
//        channel.call(new WindowsSlaveInstaller(remoteFs));
        for (ComputerListener cl : ComputerListener.all())
            cl.preOnline(this,channel,root,taskListener);

        offlineCause = null;

        // update the data structure atomically to prevent others from seeing a channel that's not properly initialized yet
        synchronized(channelLock) {
            if(this.channel!=null) {
                // check again. we used to have this entire method in a big sycnhronization block,
                // but Channel constructor blocks for an external process to do the connection
                // if CommandLauncher is used, and that cannot be interrupted because it blocks at InputStream.
                // so if the process hangs, it hangs the thread in a lock, and since Hudson will try to relaunch,
                // we'll end up queuing the lot of threads in a pseudo deadlock.
                // This implementation prevents that by avoiding a lock. HUDSON-1705 is likely a manifestation of this.
                channel.close();
                throw new IllegalStateException("Already connected");
            }
            isUnix = _isUnix;
            numRetryAttempt = 0;
            this.channel = channel;
            defaultCharset = Charset.forName(defaultCharsetName);

            synchronized (statusChangeLock) {
                statusChangeLock.notifyAll();
            }
        }
        for (ComputerListener cl : ComputerListener.all())
            cl.onOnline(this,taskListener);
        log.println("Slave successfully connected and online");
        Jenkins.getInstance().getQueue().scheduleMaintenance();
    }

    @Override
    public Channel getChannel() {
        return channel;
    }

    public Charset getDefaultCharset() {
        return defaultCharset;
    }

    public List<LogRecord> getLogRecords() throws IOException, InterruptedException {
        if(channel==null)
            return Collections.emptyList();
        else
            return channel.call(new Callable<List<LogRecord>,RuntimeException>() {
                public List<LogRecord> call() {
                    return new ArrayList<LogRecord>(SLAVE_LOG_HANDLER.getView());
                }
            });
    }

    public HttpResponse doDoDisconnect(@QueryParameter String offlineMessage) throws IOException, ServletException {
        if (channel!=null) {
            //does nothing in case computer is already disconnected
            checkPermission(DISCONNECT);
            offlineMessage = Util.fixEmptyAndTrim(offlineMessage);
            disconnect(OfflineCause.create(Messages._SlaveComputer_DisconnectedBy(
                    Jenkins.getAuthentication().getName(),
                    offlineMessage!=null ? " : " + offlineMessage : "")
            ));
        }
        return new HttpRedirect(".");
    }

    @Override
    public Future<?> disconnect(OfflineCause cause) {
        super.disconnect(cause);
        return Computer.threadPoolForRemoting.submit(new Runnable() {
            public void run() {
                // do this on another thread so that any lengthy disconnect operation
                // (which could be typical) won't block UI thread.
                launcher.beforeDisconnect(SlaveComputer.this, taskListener);
                closeChannel();
                launcher.afterDisconnect(SlaveComputer.this, taskListener);
            }
        });
    }

    public void doLaunchSlaveAgent(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
        if(channel!=null) {
            rsp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        connect(true);

        // TODO: would be nice to redirect the user to "launching..." wait page,
        // then spend a few seconds there and poll for the completion periodically.
        rsp.sendRedirect("log");
    }

    public void tryReconnect() {
        numRetryAttempt++;
        if(numRetryAttempt<6 || (numRetryAttempt%12)==0) {
            // initially retry several times quickly, and after that, do it infrequently.
            logger.info("Attempting to reconnect "+nodeName);
            connect(true);
        }
    }

    /**
     * Serves jar files for JNLP slave agents.
     *
     * @deprecated since 2008-08-18.
     *      This URL binding is no longer used and moved up directly under to {@link jenkins.model.Jenkins},
     *      but it's left here for now just in case some old JNLP slave agents request it.
     */
    public Slave.JnlpJar getJnlpJars(String fileName) {
        return new Slave.JnlpJar(fileName);
    }

    @WebMethod(name="slave-agent.jnlp")
    public void doSlaveAgentJnlp(StaplerRequest req, StaplerResponse res) throws IOException, ServletException {
        RequestDispatcher view = req.getView(this, "slave-agent.jnlp.jelly");
        if ("true".equals(req.getParameter("encrypt"))) {
            final ByteArrayOutputStream baos = new ByteArrayOutputStream();
            StaplerResponse temp = new ResponseImpl(req.getStapler(), new HttpServletResponseWrapper(res) {
                @Override public ServletOutputStream getOutputStream() throws IOException {
                    return new FilterServletOutputStream(baos);
                }
                @Override public PrintWriter getWriter() throws IOException {
                    throw new IllegalStateException();
                }
            });
            view.forward(req, temp);

            byte[] iv = new byte[128/8];
            new SecureRandom().nextBytes(iv);

            byte[] jnlpMac = JnlpSlaveAgentProtocol.SLAVE_SECRET.mac(getName().getBytes("UTF-8"));
            SecretKey key = new SecretKeySpec(jnlpMac, 0, /* export restrictions */ 128 / 8, "AES");
            byte[] encrypted;
            try {
                Cipher c = Secret.getCipher("AES/CFB8/NoPadding");
                c.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(iv));
                encrypted = c.doFinal(baos.toByteArray());
            } catch (GeneralSecurityException x) {
                throw new IOException2(x);
            }
            res.setContentType("application/octet-stream");
            res.getOutputStream().write(iv);
            res.getOutputStream().write(encrypted);
        } else {
            checkPermission(CONNECT);
            view.forward(req, res);
        }
    }

    @Override
    protected void kill() {
        super.kill();
        closeChannel();
        IOUtils.closeQuietly(log);
    }

    public RetentionStrategy getRetentionStrategy() {
        Slave n = getNode();
        return n==null ? RetentionStrategy.INSTANCE : n.getRetentionStrategy();
    }

    /**
     * If still connected, disconnect.
     */
    private void closeChannel() {
        // TODO: race condition between this and the setChannel method.
        Channel c = channel;
        channel = null;
        isUnix = null;
        if (c != null) {
            try {
                c.close();
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Failed to terminate channel to " + getDisplayName(), e);
            }
            for (ComputerListener cl : ComputerListener.all())
                cl.onOffline(this);
        }
    }

    @Override
    protected void setNode(Node node) {
        super.setNode(node);
        launcher = grabLauncher(node);

        // maybe the configuration was changed to relaunch the slave, so try to re-launch now.
        // "constructed==null" test is an ugly hack to avoid launching before the object is fully
        // constructed.
        if(constructed!=null) {
            if (node instanceof Slave)
                ((Slave)node).getRetentionStrategy().check(this);
            else
                connect(false);
        }
    }

    /**
     * Grabs a {@link ComputerLauncher} out of {@link Node} to keep it in this {@link Computer}.
     * The returned launcher will be set to {@link #launcher} and used to carry out the actual launch operation.
     *
     * <p>
     * Subtypes that needs to decorate {@link ComputerLauncher} can do so by overriding this method.
     * This is useful for {@link SlaveComputer}s for clouds for example, where one normally needs
     * additional pre-launch step (such as waiting for the provisioned node to become available)
     * before the user specified launch step (like SSH connection) kicks in.
     *
     * @see ComputerLauncherFilter
     */
    protected ComputerLauncher grabLauncher(Node node) {
        return ((Slave)node).getLauncher();
    }

    /**
     * Get the slave version
     */
    public String getSlaveVersion() throws IOException, InterruptedException {
        return channel.call(new SlaveVersion());
    }

    /**
     * Get the OS description.
     */
    public String getOSDescription() throws IOException, InterruptedException {
        return channel.call(new DetectOS()) ? "Unix" : "Windows";
    }

    private static final Logger logger = Logger.getLogger(SlaveComputer.class.getName());

    private static final class SlaveVersion implements Callable<String,IOException> {
        public String call() throws IOException {
            try { return Launcher.VERSION; }
            catch (Throwable ex) { return "< 1.335"; } // Older slave.jar won't have VERSION
        }
    }
    private static final class DetectOS implements Callable<Boolean,IOException> {
        public Boolean call() throws IOException {
            return File.pathSeparatorChar==':';
        }
    }

    private static final class DetectDefaultCharset implements Callable<String,IOException> {
        public String call() throws IOException {
            return Charset.defaultCharset().name();
        }
    }

    /**
     * Puts the {@link #SLAVE_LOG_HANDLER} into a separate class so that loading this class
     * in JVM doesn't end up loading tons of additional classes.
     */
    static final class LogHolder {
        /**
         * This field is used on each slave node to record log records on the slave.
         */
        static final RingBufferLogHandler SLAVE_LOG_HANDLER = new RingBufferLogHandler();
    }

    private static class SlaveInitializer implements Callable<Void,RuntimeException> {
        public Void call() {
            // avoid double installation of the handler. JNLP slaves can reconnect to the master multiple times
            // and each connection gets a different RemoteClassLoader, so we need to evict them by class name,
            // not by their identity.
            for (Handler h : LOGGER.getHandlers()) {
                if (h.getClass().getName().equals(SLAVE_LOG_HANDLER.getClass().getName()))
                    LOGGER.removeHandler(h);
            }
            LOGGER.addHandler(SLAVE_LOG_HANDLER);

            // remove Sun PKCS11 provider if present. See http://wiki.jenkins-ci.org/display/JENKINS/Solaris+Issue+6276483
            try {
                Security.removeProvider("SunPKCS11-Solaris");
            } catch (SecurityException e) {
                // ignore this error.
            }

            Channel.current().setProperty("slave",Boolean.TRUE); // indicate that this side of the channel is the slave side.
            
            return null;
        }
        private static final long serialVersionUID = 1L;
        private static final Logger LOGGER = Logger.getLogger("hudson");
    }

    /**
     * Obtains a {@link VirtualChannel} that allows some computation to be performed on the master.
     * This method can be called from any thread on the master, or from slave (more precisely,
     * it only works from the remoting request-handling thread in slaves, which means if you've started
     * separate thread on slaves, that'll fail.)
     *
     * @return null if the calling thread doesn't have any trace of where its master is.
     * @since 1.362
     */
    public static VirtualChannel getChannelToMaster() {
        if (Jenkins.getInstance()!=null)
            return MasterComputer.localChannel;

        // if this method is called from within the slave computation thread, this should work
        Channel c = Channel.current();
        if (c!=null && Boolean.TRUE.equals(c.getProperty("slave")))
            return c;

        return null;
    }
}
