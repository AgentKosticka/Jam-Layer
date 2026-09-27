package app.morphe.jam.companion;

import static org.junit.Assert.*;

import org.junit.Test;

public class ChannelAuthorityTest {

  @Test
  public void onlyExplicitMonotonicPromotionGrantsAuthority() {
    ChannelAuthority<Object> authority = new ChannelAuthority<>();
    Object primary = new Object(),
      backup = new Object();
    assertFalse(authority.permits(primary));
    assertFalse(authority.promote(null, 1));
    assertTrue(authority.promote(primary, 2));
    assertFalse(authority.permits(backup));
    assertFalse(authority.promote(backup, 2));
    assertTrue(authority.promote(backup, 3));
    assertFalse(authority.permits(primary));
    assertFalse(authority.promote(primary, 1));
    assertFalse(authority.promote(primary, 100));
    assertTrue(authority.permits(backup));
  }
}
