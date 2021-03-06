/*
 * Copyright (C) 2013, Alliance for Sustainable Energy
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package gov.nrel.bacnet.consumer;

import gov.nrel.bacnet.consumer.beans.Counter;
import gov.nrel.bacnet.consumer.beans.DatabusBean;
import gov.nrel.bacnet.consumer.beans.Delay;
import gov.nrel.bacnet.consumer.beans.MultiplyConfig;
import gov.nrel.bacnet.consumer.beans.Stream;
import gov.nrel.bacnet.consumer.beans.Times;

import java.math.BigInteger;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

import java.util.concurrent.ThreadPoolExecutor;

import com.serotonin.bacnet4j.LocalDevice;
import com.serotonin.bacnet4j.RemoteDevice;
import com.serotonin.bacnet4j.exception.BACnetException;
import com.serotonin.bacnet4j.exception.BACnetTimeoutException;
import com.serotonin.bacnet4j.exception.PropertyValueException;
import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.constructed.ObjectPropertyReference;
import com.serotonin.bacnet4j.type.enumerated.ObjectType;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;
import com.serotonin.bacnet4j.util.PropertyReferences;
import com.serotonin.bacnet4j.util.PropertyValues;

class PollDeviceTask implements Runnable {

	private static final Logger log = Logger.getLogger(PollDeviceTask.class.getName());
	private RemoteDevice rd;
	private LocalDevice m_localDevice;
	// TODO make this atomic
	private int numTimesRun = 0;
	private List<ObjectIdentifier> cachedOids = new ArrayList<ObjectIdentifier>();
	private Map<ObjectIdentifier, Integer> intervals = new HashMap<ObjectIdentifier, Integer>();
	private Map<ObjectIdentifier, MultiplyConfig> multipliers = new HashMap<ObjectIdentifier, MultiplyConfig>();
	private Map<ObjectIdentifier, Stream> streams = new HashMap<ObjectIdentifier, Stream>();
	private Random r = new Random(System.currentTimeMillis());
	private Collection<BACnetDataWriter> writers;
	private int trackableTaskId;
	private ThreadPoolExecutor recorderSvc;
	
	public PollDeviceTask(RemoteDevice d, LocalDevice local, Collection<BACnetDataWriter> writers, ThreadPoolExecutor recorderSvc) {
		this.rd = d;
		this.m_localDevice = local;
		this.writers = writers;
		this.recorderSvc = recorderSvc;
	}

	public String getDescription()
	{
		return "PollDeviceTask: " + rd + " numTimesRun " + numTimesRun;
	}

	public int getId()
	{
		return trackableTaskId;
	}

	public void setId(int id)
	{
		trackableTaskId = id;
	}


	@Override
	public void run() {
		log.info("running poll task");
		Times t = new Times();
		long start = System.currentTimeMillis();
		try {
			sendRequest(t);
		} catch(Exception e) {
			if(!(e instanceof BACnetTimeoutException)) {
				log.log(Level.WARNING, "Exception sending/receiving. device="+rd, e);
			} else
				log.log(Level.WARNING, "Device timeout="+rd);
		} finally {
			numTimesRun++;
		}
	}


	public void sendRequest(Times times) throws PropertyValueException, BACnetException, Exception {
		log.fine("Oids found: "+ cachedOids.size());
		PropertyReferences refs = new PropertyReferences();
		
		if(cachedOids.size() == 0) {
			log.info("NO need to scan device="+rd+" as no oids are registered to scan");
			return;
		}
		createOidRefs(rd, cachedOids, refs);

		log.fine("Start read properties");

		log.fine(String.format("Trying to read %d properties",
				refs.size()));

		PropertyValues pvs = new PropertyValues();
		try {
			long startReadProps = System.currentTimeMillis();
			pvs = m_localDevice.readProperties(rd, refs);
			long totalRead = System.currentTimeMillis()-startReadProps;
			times.setReadPropsTime(totalRead);
		} catch (BACnetException e) {
			log.log(Level.FINE, "exception read properties", e);
			readPropValsInBatches(refs, pvs);
		}
// should not need to explicitly skip trend logs - happens via filter file
		// if (rd.getObjectIdentifier().getObjectType().equals(ObjectType.trendLog) != false) {
		// 	log.finest("Skipping trend log: " + rd.toString());
		// }
		
		long curTime = System.currentTimeMillis();
		SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd-hh:mm:ss.SSS");
		String time = fmt.format(new Date());
		
		List<BACnetData> data = new ArrayList<BACnetData>();
		Iterator<ObjectPropertyReference> propRefs = pvs.iterator();
		while(propRefs.hasNext()) {
			printDataPoint(propRefs.next(), pvs, time, curTime, data);
		}
		recorderSvc.execute(new RecordTask(data, writers));
		// for (BACnetData datum : data) {
		// 	log.info("data: time: "+datum.curTime + " value "+datum.value);
		// }
		// log.info("launching databus writer.");
		// for (BACnetDataWriter writer : writers) {
		// 	writer.oidsDiscovered(data);
		// }
	}

	private void readPropValsInBatches(PropertyReferences refs,
			PropertyValues pvs) throws BACnetException {
		// let's try breaking it up ourselves if it fails
		List<PropertyReferences> lprs = refs.getPropertiesPartitioned(10);

		for (PropertyReferences prs : lprs) {
			log.log(Level.WARNING, "batch property read: "+prs);
			PropertyValues lpvs = m_localDevice.readProperties(rd, prs);

			for (ObjectPropertyReference opr : lpvs) {
				pvs.add(opr.getObjectIdentifier(),
						opr.getPropertyIdentifier(),
						opr.getPropertyArrayIndex(),
						lpvs.getNoErrorCheck(opr));
			}
		}
	}

	private void printDataPoint(ObjectPropertyReference next,
			PropertyValues pvs, String time, long curTime, List<BACnetData> data) {
		
		BACnetData d = new BACnetData(next.getObjectIdentifier(), tryGetValue(next.getObjectIdentifier(),
		      pvs, PropertyIdentifier.presentValue), rd.getInstanceNumber(), curTime);
		data.add(d);
	}

	private Encodable tryGetValue(ObjectIdentifier oid,
			PropertyValues pvs, PropertyIdentifier propId) {
		try {
			return pvs.get(oid, propId);
		} catch(PropertyValueException e) {
			log.fine("could not retrieve prop="+propId+" for oid="+oid+" for rd="+rd);
			return null;
		}
	}
	
	private void createOidRefs(RemoteDevice rd, List<ObjectIdentifier> oids,
			PropertyReferences refs) {
		for (ObjectIdentifier oid : oids) {
			// if (!oid.getObjectType().equals(ObjectType.trendLog))
				createOidRefsImpl(rd, oid, refs);
		}
	}

	private void createOidRefsImpl(RemoteDevice rd2, ObjectIdentifier oid, PropertyReferences refs) {
		MultiplyConfig m = multipliers.get(oid);
		int multiplier = m.getMultiply();
		int random = m.getRandom();
		//To make sure all devices are randomly and uniformly distributed, we randomize the  modulo as well so a device that
		//is every 10 minutes(and 1 minute base time) and another device every 10 minutes both might run on a different modulo and not
		//bottleneck the system at points in time.
		if(numTimesRun % multiplier == random) {
			refs.add(oid, PropertyIdentifier.presentValue);
		}
	}

	public void setCachedOids(List<ObjectIdentifier> cachedOids2) {
		this.cachedOids = cachedOids2;
	}

	public RemoteDevice getRemoteDevice() {
		return rd;
	}

	public void addInterval(ObjectIdentifier oid, int pollInSeconds) {
		intervals.put(oid, pollInSeconds);
	}
	/**
	 * 
	 * @return the delay to use
	 */
	public Delay init() {
		//find the greatest common divisor on all these integers to schedule the task at
		Collection<Integer> values = intervals.values();
		log.info("intervals="+values);
		if(values.size() == 0) {
			int seconds = 2 * 60 * 60; //2 hours * 60min/hr * 60sec/min
			int delay = r.nextInt(seconds);
			return new Delay(delay, seconds);
		}
		
		Iterator<Integer> iterator = values.iterator();
		Integer current = null;
		Integer min = Integer.MAX_VALUE;
		while(iterator.hasNext()) {
			Integer next = iterator.next();
			if(iterator.hasNext() && current == null) {
				current = next;
				next = iterator.next(); 
			}
			current = gcdThing(current, next);
			min = Math.min(current, next);
		}
		
		if(current < 15) 
			throw new IllegalStateException("The greatest common divisor of the intervals is less than 15.  value="
						+current+" for values matched in filter="+values+" it needs to be more to not stress the system");
		
		for(Entry<ObjectIdentifier, Integer> entry : intervals.entrySet()) {
			int multiply = entry.getValue() / current;
			int random = r.nextInt(multiply);
			MultiplyConfig c = new MultiplyConfig(multiply, random);
			multipliers.put(entry.getKey(), c);
		}
		
		//randomize the delay such we are between 0 and minInterval
		int delay = r.nextInt(min);
		return new Delay(delay, current);
	}
	
	private static int gcdThing(int a, int b) {
	    BigInteger b1 = new BigInteger(""+a); // there's a better way to do this. I forget.
	    BigInteger b2 = new BigInteger(""+b);
	    BigInteger gcd = b1.gcd(b2);
	    return gcd.intValue();
	}

	public List<ObjectIdentifier> getCachedOids() {
		return cachedOids;
	}

}
