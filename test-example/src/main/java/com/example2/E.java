package com.example2;

import com.example.B;
import com.example1.X;

/**
 * Class E - Uses two classes and has primary dependency on A from example2 (Level 2)
 * Dependencies:
 *   - com.example2.A (Level 2 - makes E Level 3)
 *   - com.example.B (Level 1)
 *   - com.example1.X (Level 0)
 */
public class E {
    private A a = new A();  // A from example2 (Level 2)
    private B b = new B();  // B from example (Level 1)
    private X x = new X();  // X from example1 (Level 0)
    
    public String getName() {
        return "Class E";
    }
    
    public String getAInfo() {
        return a.getName();
    }
    
    public String getBInfo() {
        return b.getName();
    }
    
    public String getXInfo() {
        return x.getName();
    }
}
