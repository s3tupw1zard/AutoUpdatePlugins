package common;

import org.junit.Test;
import velocity.Metrics;

import java.lang.reflect.Constructor;

import static org.junit.Assert.assertFalse;

/** Guards Velocity's dependency-injection boundary against shaded logger types. */
public class VelocityMetricsBoundaryTest {

    @Test
    public void factoryConstructorDoesNotExposeSlf4jTypes() {
        for (Constructor<?> constructor : Metrics.Factory.class.getDeclaredConstructors()) {
            for (Class<?> parameter : constructor.getParameterTypes()) {
                String type = parameter.getName();
                assertFalse("Velocity cannot inject a relocated logger type: " + type,
                        type.startsWith("org.slf4j.") || type.startsWith("common.slf4j."));
            }
        }
    }
}
