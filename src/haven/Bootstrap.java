/*
 *  This file is part of the Haven & Hearth game client.
 *  Copyright (C) 2009 Fredrik Tolf <fredrik@dolda2000.com>, and
 *                     Björn Johannessen <johannessen.bjorn@gmail.com>
 *
 *  Redistribution and/or modification of this file is subject to the
 *  terms of the GNU Lesser General Public License, version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  Other parts of this source tree adhere to other copying
 *  rights. Please see the file `COPYING' in the root directory of the
 *  source tree for details.
 *
 *  A copy the GNU Lesser General Public License is distributed along
 *  with the source tree of which this file is a part in the file
 *  `doc/LPGL-3'. If it is missing for any reason, please see the Free
 *  Software Foundation's website at <http://www.fsf.org/>, or write
 *  to the Free Software Foundation, Inc., 59 Temple Place, Suite 330,
 *  Boston, MA 02111-1307 USA
 */

package haven;

import java.io.*;
import java.net.*;
import java.util.*;

public class Bootstrap implements UI.Receiver, UI.Runner {
    Session sess;
    String hostname;
    int port;
    final Queue<Message> msgs = new LinkedList<Message>();
    String inituser = null;
    byte[] initcookie = null;
    byte[] inittoken = null;

    public static class Message {
	int id;
	String name;
	Object[] args;

	public Message(int id, String name, Object... args) {
	    this.id = id;
	    this.name = name;
	    this.args = args;
	}
    }

    public Bootstrap(String hostname, int port) {
	this.hostname = hostname;
	this.port = port;
    }
    
    @Override
    public String title() {
	return null;
    }
    
    public Bootstrap() {
	this(Config.defserv, Config.mainport);
	if((Config.authuser != null) && (Config.authck != null)) {
	    setinitcookie(Config.authuser, Config.authck);
	    Config.authck = null;
	} else if((Config.authuser != null) && (Config.inittoken != null)) {
	    setinittoken(Config.authuser, Config.inittoken);
	    Config.inittoken = null;
	}
    }

    public void setinitcookie(String username, byte[] cookie) {
	inituser = username;
	initcookie = cookie;
    }

    public void setinittoken(String username, byte[] token) {
	inituser = username;
	inittoken = token;
    }

    private String getpref(String name, String def) {
	return(Utils.getpref(name + "@" + hostname, def));
    }

    private void setpref(String name, String val) {
	Utils.setpref(name + "@" + hostname, val);
    }

    private static byte[] getprefb(String name, String hostname, byte[] def, boolean zerovalid) {
	String sv = Utils.getpref(name + "@" + hostname, null);
	if(sv == null)
	    return(def);
	byte[] ret = Utils.hex2byte(sv);
	if((ret.length == 0) && !zerovalid)
	    return(def);
	return(ret);
    }

    private void transtoken() {
	/* XXX: Transitory, remove when appropriate. */
	String oldtoken = getpref("savedtoken", "");
	String tokenname = getpref("tokenname", "");
	if((oldtoken.length() == 64) && (tokenname.length() > 0)) {
	    setpref("savedtoken-" + tokenname, oldtoken);
	    setpref("savedtoken", "");
	}
    }

    public static byte[] gettoken(String user, String hostname) {
	return(getprefb("savedtoken-" + user, hostname, null, false));
    }

    public static void rottokens(String user, String hostname, boolean creat, boolean rm) {
	List<String> names = new ArrayList<>(Utils.getprefsl("saved-tokens@" + hostname, new String[] {}));
	creat = creat || (!rm && names.contains(user));
	if(rm || creat)
	    names.remove(user);
	if(creat)
	    names.add(0, user);
	Utils.setprefsl("saved-tokens@" + hostname, names);
    }

    public static void settoken(String user, String hostname, byte[] token) {
	Utils.setpref("savedtoken-" + user + "@" + hostname, (token == null) ? "" : Utils.byte2hex(token));
	rottokens(user, hostname, token != null, true);
    }

    private Message getmsg() throws InterruptedException {
	Message msg;
	synchronized(msgs) {
	    while((msg = msgs.poll()) == null)
		msgs.wait();
	    return(msg);
	}
    }

    public UI.Runner run(UI ui) throws InterruptedException {
	ui.setreceiver(this);
	ui.bind(ui.root.add(new LoginScreen(hostname)), 1);
	String loginname = getpref("loginname", "");
	boolean savepw = false;
	String tokenhex;
	transtoken();
	String authserver = (Config.authserv == null) ? hostname : Config.authserv;
	int authport = Config.authport;
	retry: do {
	    byte[] cookie, token;
	    String acctname;
	    if(initcookie != null) {
		acctname = inituser;
		cookie = initcookie;
		initcookie = null;
	    } else if((inituser != null) && (inittoken != null)) {
		ui.uimsg(1, "prg", "Authenticating...");
		byte[] inittoken = this.inittoken;
		this.inittoken = null;
		authed: try(AuthClient auth = new AuthClient(authserver, authport)) {
		    if(!Arrays.equals(inittoken, getprefb("lasttoken-" + inituser, hostname, null, false))) {
			String authed = auth.trytoken(inituser, inittoken);
			setpref("lasttoken-" + inituser, Utils.byte2hex(inittoken));
			if(authed != null) {
			    acctname = authed;
			    cookie = auth.getcookie();
			    settoken(authed, hostname, auth.gettoken());
			    AccountList.storeAccount(authed, Utils.byte2hex(auth.gettoken()));
			    break authed;
			}
		    }
		    if((token = gettoken(inituser, hostname)) != null) {
			String authed = auth.trytoken(inituser, token);
			if(authed == null) {
			    settoken(inituser, hostname, null);
			} else {
			    acctname = authed;
			    cookie = auth.getcookie();
			    break authed;
			}
		    }
		    ui.uimsg(1, "error", "Launcher login expired");
		    continue retry;
		} catch(IOException e) {
		    ui.uimsg(1, "error", e.getMessage());
		    continue retry;
		}
	    } else {
		AuthClient.Credentials creds;
		ui.uimsg(1, "login");
		while(true) {
		    Message msg = getmsg();
		    if(msg.id == 1) {
			if(msg.name == "login") {
			    creds = (AuthClient.Credentials) msg.args[0];
			    savepw = (Boolean) msg.args[1];
			    loginname = creds.name();
			    break;
			}
		    }
		}
		ui.uimsg(1, "prg", "Authenticating...");
		try(AuthClient auth = new AuthClient(authserver, authport)) {
		    try {
			acctname = creds.tryauth(auth);
		    } catch(AuthClient.Credentials.AuthException e) {
			settoken(creds.name(), hostname, null);
			ui.uimsg(1, "error", e.getMessage());
			continue retry;
		    }
		    cookie = auth.getcookie();
		    if(savepw) {
			settoken(acctname, hostname, auth.gettoken());
			AccountList.storeAccount(acctname, Utils.byte2hex(auth.gettoken()));
		    }
		} catch(UnknownHostException e) {
		    ui.uimsg(1, "error", "Could not locate server");
		    continue retry;
		} catch(IOException e) {
		    ui.uimsg(1, "error", e.getMessage());
		    continue retry;
		}
	    }
	    ui.uimsg(1, "prg", "Connecting...");
	    try {
		sess = new Session(new InetSocketAddress(InetAddress.getByName(hostname), port), acctname, cookie);
		sess.ui = ui;
	    } catch(UnknownHostException e) {
		ui.uimsg(1, "error", "Could not locate server");
		continue retry;
	    }
	    Thread.sleep(100);
	    while(true) {
		if(sess.state == "") {
		    setpref("loginname", loginname);
		    rottokens(loginname, hostname, false, false);
		    break retry;
		} else if(sess.connfailed != 0) {
		    String error = sess.connerror;
		    if(error == null)
			error = "Connection failed";
		    ui.uimsg(1, "error", error);
		    sess = null;
		    continue retry;
		}
		synchronized(sess) {
		    sess.wait();
		}
	    }
	} while(true);
	ui.destroy(1);
	haven.error.ErrorHandler.setprop("usr", sess.username);
	return(new RemoteUI(sess));
    }

    public void rcvmsg(int widget, String msg, Object... args) {
	synchronized(msgs) {
	    msgs.add(new Message(widget, msg, args));
	    msgs.notifyAll();
	}
    }
}
