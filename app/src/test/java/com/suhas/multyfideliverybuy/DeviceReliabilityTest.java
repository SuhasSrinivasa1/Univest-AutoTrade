package com.suhas.multyfideliverybuy;

import org.junit.Test;

import static org.junit.Assert.*;

public class DeviceReliabilityTest {
    @Test public void recognizesVivoAndIqooManufacturers() {
        assertTrue(DeviceReliability.isVivoManufacturer("vivo"));
        assertTrue(DeviceReliability.isVivoManufacturer("VIVO"));
        assertTrue(DeviceReliability.isVivoManufacturer("iQOO"));
        assertFalse(DeviceReliability.isVivoManufacturer("Samsung"));
        assertFalse(DeviceReliability.isVivoManufacturer(""));
    }
}
