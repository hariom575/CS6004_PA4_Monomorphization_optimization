// Test7: k-object sensitivity (k=1) — container pattern.
//
// PTA of Container7.run() sees pts(this) = UNKNOWN → cannot narrow worker type.
// k-obj: at the call site box.run(), the CALLER's PTA state says:
//   pts(box) = {AllocSite<Container7> A}
//   callerState[A.worker] = {AllocSite<HardWorker7>}
// So run() dispatches to HardWorker7.doWork → MONO.
//
// Expected:
//   After CHA  : BIMORPHIC (Worker7 has 2 subtypes)
//   After PTA  : BIMORPHIC (this=UNKNOWN inside run(), field unresolvable)
//   After k-Obj: MONO (caller field state gives HardWorker7)
//
// Expected output: Working hard
// Expected transformation: box.run() → worker.doWork() inlined (HardWorker7.doWork)

interface Worker7 {
    String doWork();
}

class HardWorker7 implements Worker7 {
    @Override
    public String doWork() { return "Working hard"; }
}

class LazyWorker7 implements Worker7 {
    @Override
    public String doWork() { return "Working lazily"; }
}

class Container7 {
    Worker7 worker;

    Container7(Worker7 w) {
        this.worker = w;
    }

    String run() {
        return worker.doWork();   // PTA: pts(this)=UNKNOWN → can't resolve
                                  // k-obj: caller knows worker={HardWorker7} → MONO
    }
}

public class Test7 {
    public static void main(String[] args) {
        Worker7 w = new HardWorker7();             // AllocSite<HardWorker7>
        Container7 box = new Container7(w);         // callerState[box.worker]={HardWorker7}
        String result = null;
        for (int i = 0; i < 100000; i++) {
            result = box.run();   // k-obj looks up box.worker in caller → MONO
        }
        System.out.println(result);  // Working hard
    }
}
