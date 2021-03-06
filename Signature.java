/*
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.pokegoapi.util;
import com.google.protobuf.ByteString;
import com.pokegoapi.api.PokemonGo;
import com.pokegoapi.api.device.LocationFixes;
import com.pokegoapi.exceptions.RemoteServerException;

import java.util.Random;

import POGOProtos.Networking.Envelopes.RequestEnvelopeOuterClass;
import POGOProtos.Networking.Envelopes.SignatureOuterClass;
import POGOProtos.Networking.Platform.PlatformRequestTypeOuterClass;
import POGOProtos.Networking.Platform.Requests.SendEncryptedSignatureRequestOuterClass;

public class Signature {

	/**
	 * Given a fully built request, set the signature correctly.
	 *
	 * @param api     the api
	 * @param auth_ticket the bytes of the auth ticket
	 * @param builder the requestenvelop builder
	 */
	public static void setSignature(PokemonGo api, byte[] auth_ticket, RequestEnvelopeOuterClass.RequestEnvelope.Builder builder)
			throws RemoteServerException {

		if (builder.getAuthTicket() == null) {
			//System.out.println("Ticket == null");
			return;
		}

		long currentTime = api.currentTimeMillis();
		long timeSince = currentTime - api.getStartTime();

		Random random = new Random();

		SignatureOuterClass.Signature.Builder sigBuilder;
		sigBuilder = SignatureOuterClass.Signature.newBuilder()
				.setLocationHashByTokenSeed(getLocationHash1(api, auth_ticket))
				.setLocationHash(getLocationHash2(api))
				.setEpochTimestampMs(currentTime)
				.setTimestampMsSinceStart(timeSince)
				.setDeviceInfo(api.getDeviceInfo())
				.setIosDeviceInfo(api.getActivitySignature(random))
				.addAllLocationUpdates(LocationFixes.getDefault(api, builder, currentTime, random))
				.setField22(ByteString.copyFrom(api.getSessionHash())) // random 16 bytes
				.setField25(-8408506833887075802L);

		SignatureOuterClass.Signature.SensorUpdate sensorInfo = api.getSensorSignature(currentTime, random);
		if (sensorInfo != null) {
			sigBuilder.addSensorUpdates(sensorInfo);
		}

		for (int i=0;i<builder.getRequestsList().size();i++) {
			sigBuilder.addRequestHashes(getRequestHash(builder.getRequests(i).toByteArray(), auth_ticket));
		}

		SignatureOuterClass.Signature signature = sigBuilder.build();
		byte[] sigbytes = signature.toByteArray();
		byte[] encrypted = Crypto43.encrypt(sigbytes, timeSince).toByteBuffer().array();

		ByteString signatureBytes = SendEncryptedSignatureRequestOuterClass.SendEncryptedSignatureRequest.newBuilder()
				.setEncryptedSignature(ByteString.copyFrom(encrypted)).build()
				.toByteString();

		RequestEnvelopeOuterClass.RequestEnvelope.PlatformRequest platformRequest = RequestEnvelopeOuterClass
				.RequestEnvelope.PlatformRequest.newBuilder()
				.setType(PlatformRequestTypeOuterClass.PlatformRequestType.SEND_ENCRYPTED_SIGNATURE)
				.setRequestMessage(signatureBytes)
				.build();
		builder.addPlatformRequests(platformRequest);
	}

	private static byte[] getBytes(double input) {
		long rawDouble = Double.doubleToRawLongBits(input);
		return new byte[]{
				(byte) (rawDouble >>> 56),
				(byte) (rawDouble >>> 48),
				(byte) (rawDouble >>> 40),
				(byte) (rawDouble >>> 32),
				(byte) (rawDouble >>> 24),
				(byte) (rawDouble >>> 16),
				(byte) (rawDouble >>> 8),
				(byte) rawDouble
		};
	}

	private static int getLocationHash1(PokemonGo api, byte[] auth_ticket) {
		byte[] bytes = new byte[24];
		int seed = Hasher.hash32(auth_ticket);

		System.arraycopy(getBytes(api.getLatitude()), 0, bytes, 0, 8);
		System.arraycopy(getBytes(api.getLongitude()), 0, bytes, 8, 8);
		System.arraycopy(getBytes(api.getAltitude()), 0, bytes, 16, 8);

		return Hasher.hash32salt(bytes, Hasher.intToByteArray(seed));
	}

	private static int getLocationHash2(PokemonGo api) {
		byte[] bytes = new byte[24];

		System.arraycopy(getBytes(api.getLatitude()), 0, bytes, 0, 8);
		System.arraycopy(getBytes(api.getLongitude()), 0, bytes, 8, 8);
		System.arraycopy(getBytes(api.getAltitude()), 0, bytes, 16, 8);

		return Hasher.hash32(bytes);
	}

	private static long getRequestHash(byte[] request, byte[] auth_ticket) {
		byte[] seed = ByteBuffer.allocate(8).putLong(Hasher.hash64(auth_ticket).longValue()).array();
		return Hasher.hash64salt(request, seed).longValue();
	}
}
