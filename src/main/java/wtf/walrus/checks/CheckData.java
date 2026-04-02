package wtf.walrus.checks;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface CheckData {
    String name();
    String description() default "";
    double maxBuffer() default 50.0;
    int maxVl() default 5;
    int ap() default 5;
    int decay() default 300;
}
