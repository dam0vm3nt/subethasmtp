package org.subethamail.smtp.server;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.UnknownHostException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.subethamail.smtp.AuthenticationHandlerFactory;
import org.subethamail.smtp.MessageHandlerFactory;
import org.subethamail.smtp.Version;

/**
 * Main SMTPServer class.  Construct this object, set the
 * hostName, port, and bind address if you wish to override the 
 * defaults, and call start(). 
 * 
 * This class starts opens a ServerSocket and creates a new
 * instance of the ConnectionHandler class when a new connection
 * comes in.  The ConnectionHandler then parses the incoming SMTP
 * stream and hands off the processing to the CommandHandler which
 * will execute the appropriate SMTP command class.
 *  
 * This class also manages a watchdog thread which will timeout 
 * stale connections.
 *
 * To use this class, construct a server with your implementation
 * of the MessageHandlerFactory.  This provides low-level callbacks
 * at various phases of the SMTP exchange.  For a higher-level
 * but more limited interface, you can pass in a
 * org.subethamail.smtp.helper.SimpleMessageListenerAdapter.
 * 
 * By default, no authentication methods are offered.  To use
 * authentication, set an AuthenticationHandlerFactory.
 * 
 * @author Jon Stevens
 * @author Ian McFarland &lt;ian@neo.com&gt;
 * @author Jeff Schnitzer
 */
public class SMTPServer implements Runnable
{
	private final static Logger log = LoggerFactory.getLogger(SMTPServer.class);
	
	/** Hostame used if we can't find one */
	private final static String UNKNOWN_HOSTNAME = "localhost";

	private InetAddress bindAddress = null;	// default to all interfaces
	private int port = 25;	// default to 25
	private String hostName;	// defaults to a lookup of the local address
	private int backlog = 50;

	private MessageHandlerFactory messageHandlerFactory;
	private AuthenticationHandlerFactory authenticationHandlerFactory;

	private CommandHandler commandHandler;
	
	private ServerSocket serverSocket;
	private boolean go = false;
	
	private Thread serverThread;
	private Watchdog watchdog;

	private ThreadGroup connectionHanderGroup;
	
	/** 
	 * set a hard limit on the maximum number of connections this server will accept 
	 * once we reach this limit, the server will gracefully reject new connections.
	 * Default is 1000.
	 */
	private int maxConnections = 1000;

	/**
	 * The timeout for waiting for data on a connection is one minute: 1000 * 60 * 1
	 */
	private int connectionTimeout = 1000 * 60 * 1;

	/**
	 * The maximal number of recipients that this server accepts per message delivery request.
	 */
	private int maxRecipients = 1000;
	
	/**
	 * The primary constructor.
	 */
	public SMTPServer(MessageHandlerFactory handlerFactory)
	{
		this(handlerFactory, null);
	}
	
	/**
	 * The primary constructor.
	 */
	public SMTPServer(MessageHandlerFactory msgHandlerFact, AuthenticationHandlerFactory authHandlerFact)
	{
		this.messageHandlerFactory = msgHandlerFact;
		this.authenticationHandlerFactory = authHandlerFact;
		
		try
		{
			this.hostName = InetAddress.getLocalHost().getCanonicalHostName();
		}
		catch (UnknownHostException e)
		{
			this.hostName = UNKNOWN_HOSTNAME;
		}

		this.commandHandler = new CommandHandler();		

		this.connectionHanderGroup = new ThreadGroup(SMTPServer.class.getName() + " ConnectionHandler Group");
	}

	/** @return the host name that will be reported to SMTP clients */
	public String getHostName()
	{
		if (this.hostName == null)
			return UNKNOWN_HOSTNAME;
		else
			return this.hostName;
	}

	/** The host name that will be reported to SMTP clients */
	public void setHostName(String hostName)
	{
		this.hostName = hostName;
	}

	/** null means all interfaces */
	public InetAddress getBindAddress()
	{
		return this.bindAddress;
	}

	/** null means all interfaces */
	public void setBindAddress(InetAddress bindAddress)
	{
		this.bindAddress = bindAddress;
	}

	/** */
	public int getPort()
	{
		return this.port;
	}

	public void setPort(int port)
	{
		this.port = port;
	}

	/**
	 * Is the server running after start() has been called?
	 */
	public boolean isRunning()
	{
		return this.go;
	}

	/**
	 * The backlog is the Socket backlog.
	 * 
	 * The backlog argument must be a positive value greater than 0. 
	 * If the value passed if equal or less than 0, then the default value will be assumed.
	 * 
	 * @return the backlog
	 */
	public int getBacklog()
	{
		return this.backlog;
	}

	/**
	 * The backlog is the Socket backlog.
	 * 
	 * The backlog argument must be a positive value greater than 0. 
	 * If the value passed if equal or less than 0, then the default value will be assumed. 
	 */
	public void setBacklog(int backlog)
	{
		this.backlog = backlog;
	}

	/**
	 * Call this method to get things rolling after instantiating the
	 * SMTPServer.
	 */
	public synchronized void start()
	{
		if (this.serverThread != null)
			throw new IllegalStateException("SMTPServer already started");
		
		// Create our server socket here.
		try
		{
			this.serverSocket = createServerSocket();
		}
		catch (Exception e)
		{
			throw new RuntimeException(e);
		}

		this.go = true;

		this.serverThread = new Thread(this, SMTPServer.class.getName());
		// daemon threads do not keep the program from quitting; 
		// user threads keep the program from quitting.
		// We want the serverThread to keep the program from quitting
		// this.serverThread.setDaemon(true);

		// Now call the serverThread.run() method
		this.serverThread.start();

		this.watchdog = new Watchdog();
		this.watchdog.start();
	}

	/**
	 * Shut things down gracefully.
	 */
	public synchronized void stop()
	{
		// don't accept any more connections
		this.go = false;
		
		// kill the listening thread
		this.serverThread = null;

		// stop the watchdog
		if (this.watchdog != null)
		{
			this.watchdog.quit();
			this.watchdog = null;
		}

		// Shut down any open connections.
		this.shutDownOpenConnections();
		
		// if the serverSocket is not null, force a socket close for good measure
		try
		{
			if (this.serverSocket != null && !this.serverSocket.isClosed())
				this.serverSocket.close();
		}
		catch (IOException e)
		{
		}
	}

	/**
	 * Grabs all ThreadGroup instances of ConnectionHander's and attempts to close the 
	 * socket if it is still open.
	 */
	protected void shutDownOpenConnections()
	{
		Thread[] groupThreads = new Thread[maxConnections];
		ThreadGroup connectionGroup = getConnectionGroup();

		connectionGroup.enumerate(groupThreads);
		for (int i=0; i<connectionGroup.activeCount(); i++)
		{
			Session handler = ((Session)groupThreads[i]);
			if (handler != null)
			{
				try
				{
					handler.closeSocket();
				}
				catch (IOException e)
				{
				}
			}
		}
	}

	/**
	 * Override this method if you want to create your own server sockets.
	 * You must return a bound ServerSocket instance
	 * 
	 * @throws IOException
	 */
	protected ServerSocket createServerSocket()
		throws IOException
	{
		InetSocketAddress isa;

		if (this.bindAddress == null)
		{
			isa = new InetSocketAddress(this.port);
		}
		else
		{
			isa = new InetSocketAddress(this.bindAddress, this.port);
		}

		ServerSocket serverSocket = new ServerSocket();			
		// http://java.sun.com/j2se/1.5.0/docs/api/java/net/ServerSocket.html#setReuseAddress(boolean)
		serverSocket.setReuseAddress(true);
		serverSocket.bind(isa, this.backlog);

		return serverSocket;
	}

	/**
	 * This method is called by this thread when it starts up.
	 */
	public void run()
	{
		while (this.go)
		{
			try
			{
				Session connectionHandler = new Session(this, this.serverSocket.accept());
				connectionHandler.start();
			}
			catch (IOException ioe)
			{
				if (this.go)
					log.error("Error accepting connections", ioe);
			}
		}

		try
		{
			if (this.serverSocket != null && !this.serverSocket.isClosed())
				this.serverSocket.close();
			
			log.info("SMTP Server socket shut down.");
		}
		catch (IOException e)
		{
			log.error("Failed to close server socket.", e);
		}
		this.serverSocket = null;
	}

	public String getName()
	{
		return "SubEthaSMTP";
	}

	public String getNameVersion()
	{
		return getName() + " " + Version.getSpecification();
	}

	/**
	 * @return the factory for message handlers, cannot be null
	 */
	public MessageHandlerFactory getMessageHandlerFactory()
	{
		return this.messageHandlerFactory;
	}
	
	public void setMessageHandlerFactory(MessageHandlerFactory fact)
	{
		this.messageHandlerFactory = fact;
	}
	
	/**
	 * @return the factory for auth handlers, or null if no such factory has been set.
	 */
	public AuthenticationHandlerFactory getAuthenticationHandlerFactory()
	{
		return this.authenticationHandlerFactory;
	}
	
	public void setAuthenticationHandlerFactory(AuthenticationHandlerFactory fact)
	{
		this.authenticationHandlerFactory = fact;
	}

	/**
	 * The CommandHandler manages handling the SMTP commands
	 * such as QUIT, MAIL, RCPT, DATA, etc.
	 * 
	 * @return An instance of CommandHandler
	 */
	public CommandHandler getCommandHandler()
	{
		return this.commandHandler;
	}

	protected ThreadGroup getConnectionGroup()
	{
		return this.connectionHanderGroup;
	}

	public int getNumberOfConnections()
	{
		return this.connectionHanderGroup.activeCount();
	}
	
	public boolean hasTooManyConnections()
	{
		if (maxConnections < 0)
			return false;
		else
			return (getNumberOfConnections() >= maxConnections);
	}
	
	public int getMaxConnections()
	{
		return this.maxConnections;
	}

	/**
	 * Set's the maximum number of connections this server instance will
	 * accept. A value of -1 means "unlimited".
	 * 
	 * @param maxConnections
	 */
	public void setMaxConnections(int maxConnections)
	{
		this.maxConnections = maxConnections;
	}

	public int getConnectionTimeout()
	{
		return this.connectionTimeout;
	}

	/**
	 * Set the number of milliseconds that the server will wait for
	 * client input.  Sometime after this period expires, an client will
	 * be rejected and the connection closed.
	 */
	public void setConnectionTimeout(int connectionTimeout)
	{
		this.connectionTimeout = connectionTimeout;
	}

	public int getMaxRecipients()
	{
		return this.maxRecipients;
	}

	/**
	 * Set the maximum number of recipients allowed for each message.
	 * A value of -1 means "unlimited".
	 */
	public void setMaxRecipients(int maxRecipients)
	{
		this.maxRecipients = maxRecipients;
	}

	/**
	 * A watchdog thread that makes sure that connections don't go stale. It
	 * prevents someone from opening up MAX_CONNECTIONS to the server and
	 * holding onto them for more than 1 minute.
	 */
	private class Watchdog extends Thread
	{
		private boolean run = true;

		public Watchdog()
		{
			super(Watchdog.class.getName());
			
			// We do not want the watchdog to keep the program from quitting
			setDaemon(true);
			
			setPriority(Thread.MAX_PRIORITY / 3);
		}

		public void quit()
		{
			this.run = false;
		}

		public void run()
		{
			while (this.run)
			{
				Thread[] groupThreads = new Thread[maxConnections];
				ThreadGroup connectionGroup = getConnectionGroup();	// from parent class
				connectionGroup.enumerate(groupThreads);

				for (int i=0; i<connectionGroup.activeCount(); i++)
				{
					Session aThread = ((Session)groupThreads[i]);
					if (aThread != null)
					{
						aThread.checkForIdle();
					}
				}
				try
				{
					// go to sleep for 10 seconds.
					sleep(1000 * 10);
				}
				catch (InterruptedException e)
				{
					// ignore
				}
			}
		}
	}
}
