"""Regenerate deterministic Android Z-A LDN protocol-1 fixtures with vendored kinnay/LDN."""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "vendor" / "LDN"))
import ldn  # noqa: E402

OUT = ROOT / "android/app/src/test/resources/za"
KEYS = {
    "aes_kek_generation_source": bytes(range(0x00, 0x10)),
    "aes_key_generation_source": bytes(range(0x10, 0x20)),
    "master_key_00": bytes(range(0x20, 0x30)),
    "master_key_12": bytes(range(0x30, 0x40)),
}
SSID = bytes(range(0x40, 0x50))
SERVER_RANDOM = bytes(range(0x50, 0x60))
BSSID = bytes.fromhex("021122334455")
NONCE = bytes.fromhex("60616263")
TOKEN = 0x0102030405060708
DEVICE_ID = 0x1112131415161718
CLIENT_RANDOM = bytes(range(0x70, 0x80))

network_id = ldn.NetworkId(0x0100F43008C44000, 1, SSID)
keys = ldn.KeyDerivation(KEYS, 1)
info = ldn.AdvertisementInfo()
info.server_random = SERVER_RANDOM
info.band = 2
info.channel = 6
info.max_participants = 2
info.num_participants = 1
info.participants = [ldn.ParticipantInfo() for _ in range(8)]
info.participants[0] = ldn.ParticipantInfo(
    "169.254.33.1", ldn.MACAddress(BSSID), True, b" ", 6, ldn.PLATFORM_NX
)
info.application_data = (OUT / "advertisement_12345678.bin").read_bytes()
info.challenge = TOKEN
advertisement = ldn.AdvertisementFrame(keys, 1)
advertisement.network_id = network_id
advertisement.version = 4
advertisement.format = ldn.ADVERTISE_FORMAT_AES_CTR
advertisement.nonce = NONCE
advertisement.payload = info
action = bytes.fromhex("d0000000ffffffffffff") + BSSID + bytes.fromhex("ffffffffffff0000") + advertisement.encode()
(OUT / "protocol1_action.bin").write_bytes(action)
info.num_participants = 2
info.participants[1] = ldn.ParticipantInfo(
    "169.254.33.2", ldn.MACAddress(bytes.fromhex("0a0b0c0d0e0f")),
    True, b"Console", 6, ldn.PLATFORM_NX
)
advertisement.nonce = bytes.fromhex("60616264")
registered_action = (bytes.fromhex("d0000000ffffffffffff") + BSSID +
                     bytes.fromhex("ffffffffffff0000") + advertisement.encode())
(OUT / "protocol1_registered_action.bin").write_bytes(registered_action)

challenge = ldn.ChallengeRequest(token=TOKEN, nonce=0x2122232425262728,
                                 device_id=0x3132333435363738)
request = ldn.AuthenticationFrame(keys, 1)
request.version = 4
request.network_id = network_id
request.server_random = SERVER_RANDOM
request.client_random = CLIENT_RANDOM
request.payload = ldn.AuthenticationRequest(b"Console", 6, ldn.PLATFORM_NX,
                                             challenge.encode(ldn.CHALLENGE_KEY))
(OUT / "protocol1_auth_request.bin").write_bytes(request.encode())

response = ldn.AuthenticationFrame(keys, 1)
response.version = 4
response.network_id = network_id
response.server_random = SERVER_RANDOM
response.client_random = CLIENT_RANDOM
response.status_code = 0
response.payload = ldn.AuthenticationResponse(
    ldn.PLATFORM_NX,
    ldn.ChallengeResponse(flags=2, nonce=challenge.nonce, device_id=challenge.device_id,
                          device_id_host=DEVICE_ID).encode(ldn.CHALLENGE_KEY),
)
(OUT / "protocol1_auth_response.bin").write_bytes(response.encode())
print("Generated protocol-1 fixtures with vendored kinnay/LDN")
