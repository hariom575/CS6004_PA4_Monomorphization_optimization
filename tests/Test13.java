// Test13: MEGA -- six subtypes, no optimization applied.
//
// Protocol13 has 6 concrete implementations, all allocated via a switch.
// CHA gives 6 edges. 6 > MEGA_THRESHOLD(4), so the call site is skipped entirely.
//
// No analysis layer helps here: the site is genuinely MEGA at every level.
// This verifies that the pass correctly leaves MEGA sites alone rather than
// generating an excessively long type-test chain.
//
// Expected:
//   After CHA/VTA : MEGA (6 targets)
//   After PTA     : MEGA (all 6 branches reachable, pts(p) = {HTTP,HTTPS,FTP,SMTP,TCP,UDP})
//   After k-obj   : MEGA (no field involved)
//   Transformation: SKIPPED
//
// Expected output: HTTP:data  (no args -> args.length=0 -> case 0 -> HTTP13)

interface Protocol13 {
    String handle(String msg);
}

class HTTP13  implements Protocol13 { public String handle(String msg) { return "HTTP:"  + msg; } }
class HTTPS13 implements Protocol13 { public String handle(String msg) { return "HTTPS:" + msg; } }
class FTP13   implements Protocol13 { public String handle(String msg) { return "FTP:"   + msg; } }
class SMTP13  implements Protocol13 { public String handle(String msg) { return "SMTP:"  + msg; } }
class TCP13   implements Protocol13 { public String handle(String msg) { return "TCP:"   + msg; } }
class UDP13   implements Protocol13 { public String handle(String msg) { return "UDP:"   + msg; } }

public class Test13 {
    public static void main(String[] args) {
        Protocol13 p;
        switch (args.length % 6) {
            case 0:  p = new HTTP13();  break;
            case 1:  p = new HTTPS13(); break;
            case 2:  p = new FTP13();   break;
            case 3:  p = new SMTP13();  break;
            case 4:  p = new TCP13();   break;
            default: p = new UDP13();   break;
        }
        String result = null;
        for (int i = 0; i < 100000; i++) {
            result = p.handle("data");  // MEGA: 6 targets -> no rewrite
        }
        System.out.println(result);  // HTTP:data
    }
}
