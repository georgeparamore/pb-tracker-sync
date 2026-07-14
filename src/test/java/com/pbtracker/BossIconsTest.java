package com.pbtracker;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.ImageIcon;
import javax.swing.SwingUtilities;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class BossIconsTest
{
	@Test
	public void borrowedTrackedBossIconsResolveToSprites()
	{
		for (String boss : List.of(
			"Leviathan (awakened)",
			"Whisperer (awakened)",
			"Tzhaar-Ket-Rak's First Challenge",
			"Tzhaar-Ket-Rak's Second Challenge",
			"Tzhaar-Ket-Rak's Third Challenge",
			"Tzhaar-Ket-Rak's Fourth Challenge",
			"Tzhaar-Ket-Rak's Fifth Challenge",
			"Tzhaar-Ket-Rak's Sixth Challenge"))
		{
			assertNotNull(boss + " should resolve to a borrowed sprite", BossIcons.spriteIdFor(boss));
		}
	}

	@Test
	public void callbacksAreDeliveredOnSwingEventThread() throws Exception
	{
		CountDownLatch delivered = new CountDownLatch(1);
		AtomicBoolean deliveredOnEdt = new AtomicBoolean();
		Thread caller = new Thread(() -> BossIcons.deliverOnEdt(icon ->
		{
			deliveredOnEdt.set(SwingUtilities.isEventDispatchThread());
			delivered.countDown();
		}, new ImageIcon()), "boss-icon-test-caller");

		caller.start();
		caller.join();

		assertTrue("icon callback was not delivered", delivered.await(2, TimeUnit.SECONDS));
		assertTrue("icon callback must run on the Swing EDT", deliveredOnEdt.get());
	}
}
