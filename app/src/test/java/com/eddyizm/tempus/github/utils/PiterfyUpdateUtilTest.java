package com.eddyizm.tempus.github.utils;
import org.junit.Test;
import static org.junit.Assert.*;
public class PiterfyUpdateUtilTest {
 @Test public void comparesForkRevision(){assertTrue(UpdateUtil.isNewer("4.29.0-piterfy.2","v4.29.0-piterfy.10"));}
 @Test public void doesNotDowngrade(){assertFalse(UpdateUtil.isNewer("4.29.0-piterfy.2","v4.29.0-piterfy.1"));}
 @Test public void detectsBaseUpgrade(){assertTrue(UpdateUtil.isNewer("4.29.0-piterfy.9","v4.30.0-piterfy.1"));}
 @Test public void ignoresUpstreamAndPrereleases(){assertFalse(UpdateUtil.isNewer("4.29.0-piterfy.2","v4.30.0"));assertFalse(UpdateUtil.isNewer("4.29.0-piterfy.2","v4.30.0-piterfy.1-dev"));}
 @Test public void equalAndMalformedAreNotUpdates(){assertFalse(UpdateUtil.isNewer("4.29.0-piterfy.2","v4.29.0-piterfy.2"));assertFalse(UpdateUtil.isNewer("4.29.0-piterfy.2",null));}
}
