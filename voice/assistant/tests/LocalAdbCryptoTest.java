import xyz.erapi0neer.xingyi.LocalAdb;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.util.*;
public class LocalAdbCryptoTest {
  static void check(boolean value, String message) { if(!value) throw new AssertionError(message); }
  static BigInteger fromLE(byte[] value) { byte[] rev=value.clone(); for(int i=0,j=rev.length-1;i<j;i++,j--){byte b=rev[i];rev[i]=rev[j];rev[j]=b;}return new BigInteger(1,rev); }
  public static void main(String[] args) throws Exception {
    KeyPairGenerator gen=KeyPairGenerator.getInstance("RSA");gen.initialize(2048);KeyPair pair=gen.generateKeyPair();
    RSAPrivateCrtKey key=(RSAPrivateCrtKey)pair.getPrivate();
    Method sign=LocalAdb.class.getDeclaredMethod("signToken",RSAPrivateKey.class,byte[].class);sign.setAccessible(true);
    byte[] message="non-sensitive ADB auth test".getBytes(StandardCharsets.UTF_8);
    byte[] token=MessageDigest.getInstance("SHA-1").digest(message);
    byte[] signature=(byte[])sign.invoke(null,key,token);
    Signature verifier=Signature.getInstance("SHA1withRSA");verifier.initVerify(pair.getPublic());verifier.update(message);
    check(verifier.verify(signature),"AUTH signature must verify SHA1 exactly once");
    verifier.initVerify(pair.getPublic());verifier.update(token);check(!verifier.verify(signature),"AUTH must not hash token again");
    Method pub=LocalAdb.class.getDeclaredMethod("publicKeyMessage",RSAPrivateKey.class);pub.setAccessible(true);
    byte[] wire=(byte[])pub.invoke(null,key);check(wire[wire.length-1]==0,"public key NUL terminator");
    String text=new String(wire,StandardCharsets.UTF_8);
    byte[] raw=Base64.getDecoder().decode(text.substring(0,text.indexOf(' ')));
    check(raw.length==524,"public key struct size");ByteBuffer b=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
    check(b.getInt()==64,"modulus words");long inv=Integer.toUnsignedLong(b.getInt());
    byte[] modulus=new byte[256];b.get(modulus);check(fromLE(modulus).equals(key.getModulus()),"LE modulus");
    BigInteger two32=BigInteger.ONE.shiftLeft(32);
    check(key.getModulus().multiply(BigInteger.valueOf(inv)).mod(two32).equals(two32.subtract(BigInteger.ONE)),"n0inv");
    byte[] rr=new byte[256];b.get(rr);check(fromLE(rr).equals(BigInteger.ONE.shiftLeft(4096).mod(key.getModulus())),"Montgomery R squared");
    check(b.getInt()==65537,"public exponent");
    System.out.println("PASS: AUTH signature, no double hash, NUL termination, 524-byte public key, modulus, n0inv, R squared, exponent");
  }
}
