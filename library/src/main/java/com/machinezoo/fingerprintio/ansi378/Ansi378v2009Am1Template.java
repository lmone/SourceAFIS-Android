// Part of FingerprintIO: https://fingerprintio.machinezoo.com
package com.machinezoo.fingerprintio.ansi378;

import java.util.*;
import org.slf4j.*;
import com.machinezoo.fingerprintio.common.*;
import com.machinezoo.fingerprintio.utils.*;

import java8.util.Comparators;
import java8.util.stream.Collectors;
import java8.util.stream.StreamSupport;

/*
 * Object model of ANSI INCITS 378-2009/AM1 template.
 */
public class Ansi378v2009Am1Template {
	private static final Logger logger = LoggerFactory.getLogger(Ansi378v2009Am1Template.class);
	private static final byte[] magic = new byte[] { 'F', 'M', 'R', 0, '0', '3', '5', 0 };
	public static boolean accepts(byte[] template) {
		return template.length >= magic.length && Arrays.equals(magic, Arrays.copyOf(template, magic.length));
	}
	public int vendorId = BiometricOrganizations.UNKNOWN;
	public int subformat;
	public boolean sensorCertified;
	public int sensorId;
	public List<Ansi378v2009Am1Fingerprint> fingerprints = new ArrayList<>();
	public Ansi378v2009Am1Template() {
	}
	public Ansi378v2009Am1Template(byte[] template) {
		this(template, false);
	}
	public Ansi378v2009Am1Template(byte[] template, boolean lax) {
		if (!accepts(template)) {
			if (lax && Ansi378v2009Template.accepts(template)) {
				template = Arrays.copyOf(template, template.length);
				template[6] = '5';
			} else
				throw new TemplateFormatException("This is not an ANSI INCITS 378-2009/AM1 template.");
		}
		TemplateUtils.decodeTemplate(template, in -> {
			in.skipBytes(magic.length);
			long length = 0xffff_ffffL & in.readInt();
			Validate.condition(length >= 21, "Total length must be at least 21 bytes.");
			Validate.condition(length <= magic.length + 4 + in.available(), true, "Total length indicates trimmed template.");
			vendorId = in.readUnsignedShort();
			subformat = in.readUnsignedShort();
			int certification = in.readUnsignedByte();
			sensorCertified = (certification & 0x80) != 0;
			if ((certification & 0x7f) != 0)
				logger.warn("Ignoring unrecognized sensor compliance bits.");
			sensorId = in.readUnsignedShort();
			int count = in.readUnsignedByte();
			in.skipBytes(1);
			for (int i = 0; i < count; ++i)
				fingerprints.add(new Ansi378v2009Am1Fingerprint(in, lax));
			if (in.available() > 0)
				logger.debug("Ignored extra data at the end of the template.");
			Validate.template(this::validate, lax);
		});
	}
	public byte[] toByteArray() {
		validate();
		DataOutputBuffer out = new DataOutputBuffer();
		out.write(magic);
		out.writeInt(measure());
		out.writeShort(vendorId);
		out.writeShort(subformat);
		out.writeByte(sensorCertified ? 0x80 : 0);
		out.writeShort(sensorId);
		out.writeByte(fingerprints.size());
		out.writeByte(0);
		for (Ansi378v2009Am1Fingerprint fp : fingerprints)
			fp.write(out);
		return out.toByteArray();
	}
	public Ansi378v2009Template downgrade() {
		byte[] serialized = toByteArray();
		serialized[6] = '0';
		return new Ansi378v2009Template(serialized);
	}
	private int measure() {
		// src: return 21 + fingerprints.stream().mapToInt(Ansi378v2009Am1Fingerprint::measure).sum();
		return 21 + StreamSupport.stream(fingerprints).mapToInt(Ansi378v2009Am1Fingerprint::measure).sum();
	}
	private void validate() {
		Validate.nonzero16(vendorId, "Vendor ID must be a non-zero unsigned 16-bit number.");
		Validate.int16(subformat, "Vendor subformat must be an unsigned 16-bit number.");
		Validate.int16(sensorId, "Sensor ID must be an unsigned 16-bit number.");
		Validate.int8(fingerprints.size(), "There cannot be more than 255 fingerprints.");
		Validate.nonzero(fingerprints.size(), "At least one fingerprint must be present in the template.");
		for (Ansi378v2009Am1Fingerprint fp : fingerprints)
			fp.validate();
		if (fingerprints.size() != StreamSupport.stream(fingerprints).map(fp -> (fp.position.ordinal() << 16) + fp.view).distinct().count())
			throw new TemplateFormatException("Every fingerprint must have a unique combination of finger position and view offset.");
		StreamSupport.stream(StreamSupport.stream(fingerprints)
			.collect(Collectors.groupingBy(fp -> fp.position))
			.values())
			.forEach(l -> {
				for (int i = 0; i < l.size(); ++i) {
					Validate.range(l.get(i).view, 0, l.size() - 1, "Fingerprint view numbers must be assigned contiguously, starting from zero.");
					if (!l.equals(StreamSupport.stream(l).sorted(Comparators.comparingInt(fp -> fp.view)).collect(Collectors.toList())))
						throw new TemplateFormatException("Fingerprints with the same finger position must be sorted by view number.");
					if (fingerprints.indexOf(l.get(0)) + l.size() - 1 != fingerprints.indexOf(l.get(l.size() - 1)))
						throw new TemplateFormatException("Fingerprints with the same finger position must be listed in the template together.");
				}
			});
	}
}
