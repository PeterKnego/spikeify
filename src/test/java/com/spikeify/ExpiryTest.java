package com.spikeify;

import com.aerospike.client.Bin;
import com.aerospike.client.IAerospikeClient;
import com.aerospike.client.Key;
import com.aerospike.client.policy.WritePolicy;
import com.spikeify.entity.EntityExpires;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.Date;
import java.util.Random;

@SuppressWarnings({"unchecked", "UnusedAssignment"})
public class ExpiryTest extends SpikeifyTest {

	private final Long userKey1 = new Random().nextLong();
	private final Long userKey2 = new Random().nextLong();
	private final String setName = "newTestSet";

	@Test
	public void defaultDbExpires() {

		long defaultTTLmsec = 1000 * sfy.info().getDefaultTTL(namespace);

		EntityExpires entity = new EntityExpires();
		entity.expires = 0L;
		Key key1 = new Key(namespace, setName, userKey1);

		Key saveKey = sfy
				.update(key1, entity)
				.now();

		EntityExpires reloaded = sfy.get(EntityExpires.class).key(saveKey).now();

		if (defaultTTLmsec == 0) {  // namespace is set to never expire
			Assert.assertEquals(reloaded.expires.longValue(), -1L);
		} else {
			long now = new Date().getTime();
			Assert.assertTrue(now > reloaded.expires - defaultTTLmsec);
			Assert.assertTrue(reloaded.expires > now);
		}

	}

	@Test
	public void doesNotExpire() {
		EntityExpires entity = new EntityExpires();
		entity.expires = -1L;
		Key key1 = new Key(namespace, setName, userKey1);

		// we did not provide namespace on purpose - let default kick in
		Key saveKey = sfy
				.update(key1, entity)
				.now();

		EntityExpires reloaded = sfy.get(EntityExpires.class).key(saveKey).now();
		Assert.assertEquals(entity.expires, reloaded.expires);
		Assert.assertEquals(-1L, reloaded.expires.longValue());
	}

	@Test
	public void setExpires() {
		EntityExpires entity = new EntityExpires();

		long milliSecDay = 24L * 60L * 60L * 1000L;
		long milliSecYear = 365L * milliSecDay;
		long futureDate = System.currentTimeMillis() + (5L * milliSecYear);
		entity.expires = futureDate;
		Key key1 = new Key(namespace, setName, userKey1);

		Key saveKey = sfy
				.update(key1, entity)
				.now();

		EntityExpires reloaded = sfy.get(EntityExpires.class).key(saveKey).now();
		Assert.assertEquals(futureDate, entity.expires.longValue());
		Assert.assertEquals(entity.expires, reloaded.expires, 5000);
		Assert.assertEquals(futureDate, reloaded.expires, 5000);
	}

	@Test
	public void setExpiresLowLevel() {

		Bin binTest = new Bin("one", 111);

		int expirationSec = 100;

		Key key = new Key(namespace, setName, userKey1);
		WritePolicy wp = new WritePolicy();
		wp.expiration = expirationSec;
		sfy.getClient().put(wp, key, binTest);

		int absExp = sfy.getClient().get(null, key).expiration;
		int relExp = ExpirationUtils.getExpiresRelative(absExp);

		Assert.assertTrue(expirationSec - relExp < 5);
	}

	/**
	 * Set relative expiration date problem. Test is successful, but record will never expire.
	 * <p>
	 * expire value is mapped correctly by Spikeify as 86400000.
	 * But Spikeify saves the expire value in AS as -1453219341 and AS logs following warning:
	 * WARNING (rw): (thr_rw.c::3136) {test} ttl 2841748150 exceeds 315360000 - set config value max-ttl to suppress this warning <Digest>:0x5da19e0a4e90067b2eada5a63afbfd21a71c44b4
	 * So record will never expire.
	 */
	@Test
	public void setExpiresRelative() {
		EntityExpires entity = new EntityExpires();

		long relativeDate = 24L * 60L * 60L * 1000L; //expire in one day
		entity.expires = relativeDate;
		Key key1 = new Key(namespace, setName, userKey1);

		Key saveKey = sfy
				.update(key1, entity)
				.now();

		EntityExpires reloaded = sfy.get(EntityExpires.class).key(saveKey).now();
		Assert.assertEquals(relativeDate, reloaded.expires, 5000); //test succeeds
	}

	/**
	 * sfy.command automatically sets expire to -1, which is wrong.
	 */
	@Test
	public void setExpiresCommandRetrieveFlow() {
		EntityExpires entity = new EntityExpires();

		long milliSecDay = 24L * 60L * 60L * 1000L;
		long milliSecYear = 365L * milliSecDay;
		long futureDate = new Date().getTime() + 5L * milliSecYear;
		entity.expires = futureDate;
		final Key key1 = new Key(namespace, setName, userKey1);

		Key saveKey = sfy
				.update(key1, entity)
				.now();

		sfy.command(EntityExpires.class).key(key1).add("one", 1).now(); //Error: it sets expire to -1

		EntityExpires reloaded = sfy.get(EntityExpires.class).key(saveKey).now();

		Assert.assertEquals(futureDate, entity.expires.longValue());
		Assert.assertEquals(entity.expires, reloaded.expires, 1);
		Assert.assertEquals(futureDate, reloaded.expires, 1);
	}

	/**
	 * sfy.update does change expire time, which is correct.
	 */
	@Test
	public void setExpiresUpdateRetrieveFlow() {
		EntityExpires entity = new EntityExpires();

		long milliSecDay = 24L * 60L * 60L * 1000L;
		long milliSecYear = 365L * milliSecDay;
		long futureDate = new Date().getTime() + 5L * milliSecYear;
		entity.expires = futureDate;
		final Key key1 = new Key(namespace, setName, userKey1);

		final Key saveKey = sfy
				.update(key1, entity)
				.now();

		//update one field
		for (int i = 0; i < 100; i++) {
			sfy.transact(5, new Work<EntityExpires>() {
				@Override
				public EntityExpires run() {
					EntityExpires obj = sfy.get(EntityExpires.class).key(key1).now();
					if (obj != null) {
						obj.one++;
						sfy.update(obj).now();
					}
					return obj;
				}
			});
		}

		EntityExpires reloaded = sfy.get(EntityExpires.class).key(saveKey).now();

		System.out.println("original: " + new Date(entity.expires.longValue()));
		System.out.println("reloaded: " + new Date(reloaded.expires.longValue()));
		System.out.println("future: " + new Date(futureDate));

		System.out.println("diff: " + (entity.expires.longValue() - reloaded.expires.longValue()));

		Assert.assertEquals(reloaded.one, 100);
		Assert.assertEquals(futureDate, entity.expires.longValue());
		Assert.assertEquals(futureDate, reloaded.expires.longValue(), 500000);
		Assert.assertEquals(entity.expires, reloaded.expires, 500000);
	}


}
