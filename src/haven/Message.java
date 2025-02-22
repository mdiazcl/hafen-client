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

import java.util.*;
import java.awt.Color;

public abstract class Message {
    public static final int T_END = 0;
    public static final int T_INT = 1;
    public static final int T_STR = 2;
    public static final int T_COORD = 3;
    public static final int T_UINT8 = 4;
    public static final int T_UINT16 = 5;
    public static final int T_COLOR = 6;
    public static final int T_TTOL = 8;
    public static final int T_INT8 = 9;
    public static final int T_INT16 = 10;
    public static final int T_NIL = 12;
    public static final int T_UID = 13;
    public static final int T_BYTES = 14;
    public static final int T_FLOAT32 = 15;
    public static final int T_FLOAT64 = 16;
    public static final int T_FCOORD32 = 18;
    public static final int T_FCOORD64 = 19;

    private final static byte[] empty = new byte[0];
    public int rh = 0, rt = 0, wh = 0, wt = 0;
    public byte[] rbuf = empty, wbuf = empty;

    public static final Message nil = new Message() {
	    public boolean underflow(int hint) {return(false);}
	    public void overflow(int min) {throw(new RuntimeException("nil message is not writable"));}
	    public String toString() {return("Message(nil)");}
	};

    public static class BinError extends RuntimeException {
	public Message msg;

	public BinError(String message) {
	    super(message);
	}
	public BinError(String message, Throwable cause) {
	    super(message, cause);
	}
	public BinError(Throwable cause) {
	    super(cause);
	}

	public BinError msg(Message msg) {
	    if(msg instanceof java.io.Serializable)
		this.msg = msg;
	    return(this);
	}
    }
    public static class EOF extends BinError {
	public EOF(String message) {
	    super(message);
	}
    }
    public static class FormatError extends BinError {
	public FormatError(String message) {
	    super(message);
	}
	public FormatError(String message, Throwable cause) {
	    super(message, cause);
	}
    }

    public abstract boolean underflow(int hint);

    private void rensure(int len) {
	while(len > rt - rh) {
	    if(!underflow(rh + len - rt))
		throw(new EOF("Required " + len + " bytes, got only " + (rt - rh)).msg(this));
	}
    }
    private int rget(int len) {
	rensure(len);
	int co = rh;
	rh += len;
	return(co);
    }

    public boolean eom() {
	return(!((rh < rt) || underflow(1)));
    }

    public int int8() {
	rensure(1);
	return(rbuf[rh++]);
    }
    public int uint8() {
	return(int8() & 0xff);
    }
    public int int16() {
	int off = rget(2);
	return(Utils.int16d(rbuf, off));
    }
    public int uint16() {
	int off = rget(2);
	return(Utils.uint16d(rbuf, off));
    }
    public int int32() {
	int off = rget(4);
	return(Utils.int32d(rbuf, off));
    }
    public long uint32() {
	int off = rget(4);
	return(Utils.uint32d(rbuf, off));
    }
    public long int64() {
	int off = rget(8);
	return(Utils.int64d(rbuf, off));
    }
    public String string() {
	int l = 0;
	while(true) {
	    if(l >= rt - rh) {
		if(!underflow(256))
		    throw(new EOF("Found no NUL (at length " + l + ")").msg(this));
	    }
	    if(rbuf[l + rh] == 0) {
		String ret = new String(rbuf, rh, l, Utils.utf8);
		rh += l + 1;
		return(ret);
	    }
	    l++;
	}
    }
    public void skip(int n) {
	while(n > 0) {
	    if(rh >= rt) {
		if(!underflow(Math.min(n, 1024)))
		    throw(new EOF("Out of bytes to skip").msg(this));
	    }
	    int s = Math.min(n, rt - rh);
	    rh += s;
	    n -= s;
	}
    }
    public void skip() {
	do rh = rt; while(underflow(1024));
    }
    public byte[] bytes(int n) {
	byte[] ret = new byte[n];
	rensure(n);
	System.arraycopy(rbuf, rh, ret, 0, n);
	rh += n;
	return(ret);
    }
    public byte[] bytes() {
	while(underflow(65536));
	return(bytes(rt - rh));
    }
    public void bytes(byte[] b, int off, int len) {
	int olen = len;
	while(len > 0) {
	    if(rh >= rt) {
		if(!underflow(Math.min(len, 1024)))
		    throw(new EOF("Required " + olen + " bytes, got only " + (olen - len)).msg(this));
	    }
	    int r = Math.min(len, rt - rh);
	    System.arraycopy(rbuf, rh, b, off, r);
	    rh += r;
	    off += r;
	    len -= r;
	}
    }
    public void bytes(byte[] b) {bytes(b, 0, b.length);}
    public Coord coord() {
	return(new Coord(int32(), int32()));
    }
    public Color color() {
	return(new Color(uint8(), uint8(), uint8(), uint8()));
    }
    public float float8() {
	return(Utils.mfdec((byte)int8()));
    }
    public float float16() {
	return(Utils.hfdec((short)int16()));
    }
    public float float32() {
	int off = rget(4);
	return(Utils.float32d(rbuf, off));
    }
    public double float64() {
	int off = rget(8);
	return(Utils.float64d(rbuf, off));
    }
    public double cpfloat() {
	int off = rget(5);
	return(Utils.floatd(rbuf, off));
    }

    public float snorm8() {
	return(Utils.clip(int8(), -0x7f, 0x7f) / 0x7fp0f);
    }
    public float unorm8() {
	return(uint8() / 0xffp0f);
    }
    public float snorm16() {
	return(Utils.clip(int16(), -0x7fff, 0x7fff) / 0x7fffp0f);
    }
    public float unorm16() {
	return(uint16() / 0xffffp0f);
    }
    public double snorm32() {
	return(Utils.clip(int32(), -0x7fffffff, 0x7fffffff) / 0x7fffffffp0);
    }
    public double unorm32() {
	return(uint32() / 0xffffffffp0);
    }

    public Object[] list() {
	ArrayList<Object> ret = new ArrayList<Object>();
	list: while(true) {
	    if(eom())
		break;
	    int t = uint8();
	    switch(t) {
	    case T_END:
		break list;
	    case T_INT:
		ret.add(int32());
		break;
	    case T_STR:
		ret.add(string());
		break;
	    case T_COORD:
		ret.add(coord());
		break;
	    case T_UINT8:
		ret.add(uint8());
		break;
	    case T_UINT16:
		ret.add(uint16());
		break;
	    case T_INT8:
		ret.add(int8());
		break;
	    case T_INT16:
		ret.add(int16());
		break;
	    case T_COLOR:
		ret.add(color());
		break;
	    case T_TTOL:
		ret.add(list());
		break;
	    case T_NIL:
		ret.add(null);
		break;
	    case T_UID:
		ret.add(int64());
		break;
	    case T_BYTES:
		int len = uint8();
		if((len & 128) != 0)
		    len = int32();
		ret.add(bytes(len));
		break;
	    case T_FLOAT32:
		ret.add(float32());
		break;
	    case T_FLOAT64:
		ret.add(float64());
		break;
	    case T_FCOORD32:
		ret.add(new Coord2d(float32(), float32()));
		break;
	    case T_FCOORD64:
		ret.add(new Coord2d(float64(), float64()));
		break;
	    default:
		throw(new FormatError("Encountered unknown type " + t + " in TTO list.").msg(this));
	    }
	}
	return(ret.toArray());
    }

    public abstract void overflow(int min);

    private void wensure(int len) {
	if(len > wt - wh)
	    overflow(len);
    }
    private int wget(int len) {
	wensure(len);
	int co = wh;
	wh += len;
	return(co);
    }

    public Message addbytes(byte[] src, int off, int len) {
	wensure(len);
	System.arraycopy(src, off, wbuf, wh, len);
	wh += len;
	return(this);
    }
    public Message addbytes(byte[] src) {
	addbytes(src, 0, src.length);
	return(this);
    }
    public Message addint8(byte num) {
	wensure(1);
	wbuf[wh++] = num;
	return(this);
    }
    public Message adduint8(int num) {
	wensure(1);
	wbuf[wh++] = (byte)num;
	return(this);
    }
    public Message addint16(short num) {
	int off = wget(2);
	Utils.int16e(num, wbuf, off);
	return(this);
    }
    public Message adduint16(int num) {
	int off = wget(2);
	Utils.uint16e(num, wbuf, off);
	return(this);
    }
    public Message addint32(int num) {
	int off = wget(4);
	Utils.int32e(num, wbuf, off);
	return(this);
    }
    public Message adduint32(long num) {
	int off = wget(4);
	Utils.uint32e(num, wbuf, off);
	return(this);
    }
    public Message addint64(long num) {
	int off = wget(8);
	Utils.int64e(num, wbuf, off);
	return(this);
    }
    public Message addstring2(String str) {
	addbytes(str.getBytes(Utils.utf8));
	return(this);
    }
    public Message addstring(String str) {
	addstring2(str); adduint8(0);
	return(this);
    }
    public Message addcoord(Coord c) {
	addint32(c.x); addint32(c.y);
	return(this);
    }
    public Message addcolor(Color color) {
	adduint8(color.getRed()); adduint8(color.getGreen());
	adduint8(color.getBlue()); adduint8(color.getAlpha());
	return(this);
    }
    public Message addfloat8(float num) {
	return(addint8(Utils.mfenc(num)));
    }
    public Message addfloat16(float num) {
	return(addint16(Utils.hfenc(num)));
    }
    public Message addfloat32(float num) {
	int off = wget(4);
	Utils.float32e(num, wbuf, off);
	return(this);
    }
    public Message addfloat64(double num) {
	int off = wget(8);
	Utils.float64e(num, wbuf, off);
	return(this);
    }

    public Message addlist(Object... args) {
	for(Object o : args) {
	    if(o == null) {
		adduint8(T_NIL);
	    } else if(o instanceof Integer) {
		adduint8(T_INT);
		addint32(((Integer)o).intValue());
	    } else if(o instanceof String) {
		adduint8(T_STR);
		addstring((String)o);
	    } else if(o instanceof Coord) {
		adduint8(T_COORD);
		addcoord((Coord)o);
	    } else if(o instanceof byte[]) {
		byte[] b = (byte[])o;
		adduint8(T_BYTES);
		if(b.length < 128) {
		    adduint8(b.length);
		} else {
		    adduint8(0x80);
		    addint32(b.length);
		}
		addbytes(b);
	    } else if(o instanceof Color) {
		adduint8(T_COLOR);
		addcolor((Color)o);
	    } else if(o instanceof Float) {
		adduint8(T_FLOAT32);
		addfloat32(((Float)o).floatValue());
	    } else if(o instanceof Double) {
		adduint8(T_FLOAT64);
		addfloat64(((Double)o).floatValue());
	    } else if(o instanceof Coord2d) {
		adduint8(T_FCOORD64);
		addfloat64(((Coord2d)o).x);
		addfloat64(((Coord2d)o).y);
	    } else if(o instanceof Object[]) {
		adduint8(T_TTOL);
		addlist((Object[])o);
		adduint8(T_END);
	    } else {
		throw(new RuntimeException("Cannot encode a " + o.getClass() + " as TTO"));
	    }
	}
	return(this);
    }

    public boolean same(Object obj) {
	if(obj instanceof Message) {
	    Message other = (Message) obj;
	    if(this.rbuf.length != other.rbuf.length) {
		return false;
	    } else {
		for(int i = 0; i < this.rbuf.length; i++) {
		    if(this.rbuf[i] != other.rbuf[i]) {
			return false;
		    }
		}
	    }
	    return true;
	}
	return false;
    }
}
