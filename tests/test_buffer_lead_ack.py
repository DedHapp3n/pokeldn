"""Regression tests for optional ACK-confirmed FRLG buffer leads."""

from pokeldn import gifts
from pokeldn.frlg.gift import file as gift_file, mg_script, mg_server
from pokeldn.frlg.gift.mystery_gift import (
    MG_LINKID_CLIENT_SCRIPT, MG_LINKID_DYNAMIC_MSG, MG_LINKID_GAME_DATA,
    MG_LINKID_RAM_SCRIPT, MG_LINKID_READY_END, MG_LINKID_RESPONSE,
)

CODE = bytes.fromhex("0100a0e31eff2fe1")
ACK = 0x4B43414F
FINAL = 0x12345678


def _game_data(code=b"BPGD"):
    data = bytearray(mg_script.GAME_DATA_SIZE)
    data[0:4] = (0x101).to_bytes(4, "little")
    data[4:6] = (1).to_bytes(2, "little")
    data[8:12] = (1).to_bytes(4, "little")
    data[12:14] = (1).to_bytes(2, "little")
    data[16:20] = (1 if code.startswith(b"BPR") else 2).to_bytes(4, "little")
    data[mg_script.GD_OFF_PLAYER_NAME] = 0xFF
    data[mg_script.GD_OFF_GAME_CODE:mg_script.GD_OFF_GAME_CODE + 4] = code
    return bytes(data)


def _send(server):
    action = server.run()
    assert action[0] == "send"
    server.on_sent()
    return action[1], action[2]


def _recv(server, ident, payload):
    assert server.run() == ("recv", ident)
    server.on_received(ident, payload)


def _start(server):
    ident, _ = _send(server)
    assert ident == MG_LINKID_CLIENT_SCRIPT
    _recv(server, MG_LINKID_GAME_DATA, _game_data())


def _commands(script):
    return [
        (int.from_bytes(script[at:at + 4], "little"),
         int.from_bytes(script[at + 4:at + 8], "little"))
        for at in range(0, len(script), 8)
    ]


def test_legacy_buffer_leads_keep_the_original_unacknowledged_script():
    legacy = mg_server.script_run_buffer_script(3)
    assert legacy[6] == (mg_server.SVR_LOAD_CLIENT_SCRIPT, mg_script.client_script_run_buffer(3))
    lead_steps = legacy[8:14]
    assert lead_steps == (
        (mg_server.SVR_LOAD_BUFFER_LEAD, 0), (mg_server.SVR_SEND,),
        (mg_server.SVR_LOAD_BUFFER_LEAD, 1), (mg_server.SVR_SEND,),
        (mg_server.SVR_LOAD_BUFFER_LEAD, 2), (mg_server.SVR_SEND,),
    )
    assert not any(step[0] == mg_server.SVR_RECV for step in lead_steps)


def test_ack_mode_uses_recv_run_send_response_for_three_leads_and_final():
    script = mg_server.script_run_buffer_script(3, ack_each_lead=True)
    loaded = [step[1] for step in script if step[0] == mg_server.SVR_LOAD_CLIENT_SCRIPT]
    chunk_scripts = [value for value in loaded
                     if value == mg_script.CLIENT_SCRIPT_RUN_BUFFER_ACK_CHUNK]
    assert len(chunk_scripts) == 4
    core = [
        (mg_script.CLI_RECV, MG_LINKID_RAM_SCRIPT),
        (mg_script.CLI_RUN_BUFFER_SCRIPT, 0),
        (mg_script.CLI_LOAD_TOSS_RESPONSE, 0),
        (mg_script.CLI_SEND_LOADED, 0),
    ]
    assert all(_commands(value)[:4] == core for value in chunk_scripts)


def test_host_waits_for_a_response_after_every_lead():
    script = mg_server.script_run_buffer_script(3, ack_each_lead=True)
    for index in range(3):
        at = script.index((mg_server.SVR_LOAD_BUFFER_LEAD, index))
        assert script[at:at + 5] == (
            (mg_server.SVR_LOAD_BUFFER_LEAD, index),
            (mg_server.SVR_SEND,),
            (mg_server.SVR_RECV, MG_LINKID_RESPONSE),
            (mg_server.SVR_READ_BUFFER_LEAD_STATUS, index),
            (mg_server.SVR_GOTO_IF_EQ, False, mg_server._SCRIPT_BUFFER_FAILURE),
        )


def test_bad_second_lead_ack_stops_before_third_lead_and_final_code():
    leads = (CODE, CODE, CODE)
    server = mg_server.MysteryGiftServer(
        buffer_code=CODE, buffer_lead=leads, buffer_ack_each_lead=True,
        buffer_lead_expect=ACK, buffer_expect=FINAL,
    )
    _start(server)
    assert _send(server)[0] == MG_LINKID_CLIENT_SCRIPT
    assert _send(server) == (MG_LINKID_RAM_SCRIPT, leads[0])
    _recv(server, MG_LINKID_RESPONSE, ACK.to_bytes(4, "little"))
    assert _send(server)[0] == MG_LINKID_CLIENT_SCRIPT
    assert _send(server) == (MG_LINKID_RAM_SCRIPT, leads[1])
    _recv(server, MG_LINKID_RESPONSE, (ACK ^ 1).to_bytes(4, "little"))
    ident, payload = _send(server)
    assert ident == MG_LINKID_CLIENT_SCRIPT
    assert payload == mg_script.CLIENT_SCRIPT_DYNAMIC_ERROR
    ident, _ = _send(server)
    assert ident == MG_LINKID_DYNAMIC_MSG
    _recv(server, MG_LINKID_READY_END, b"")
    assert server.run()[0] == "done"
    assert server.buffer_lead_statuses == [ACK, ACK ^ 1]
    assert server.buffer_status is None
    assert server.messages_sent == 7


def test_all_lead_acks_reach_final_and_buffer_expect_still_decides_verdict():
    leads = (CODE, CODE, CODE)
    server = mg_server.MysteryGiftServer(
        buffer_code=CODE, buffer_lead=leads, buffer_ack_each_lead=True,
        buffer_lead_expect=ACK, buffer_expect=FINAL,
    )
    _start(server)
    for lead in leads:
        assert _send(server)[0] == MG_LINKID_CLIENT_SCRIPT
        assert _send(server) == (MG_LINKID_RAM_SCRIPT, lead)
        _recv(server, MG_LINKID_RESPONSE, ACK.to_bytes(4, "little"))
    assert _send(server)[0] == MG_LINKID_CLIENT_SCRIPT
    assert _send(server) == (MG_LINKID_RAM_SCRIPT, CODE)
    _recv(server, MG_LINKID_RESPONSE, FINAL.to_bytes(4, "little"))
    ident, payload = _send(server)
    assert ident == MG_LINKID_CLIENT_SCRIPT
    assert payload == mg_script.CLIENT_SCRIPT_BUFFER_SUCCESS
    assert server.buffer_status == FINAL
    assert server.buffer_matched is True


def test_pokegift_roundtrip_preserves_ack_options_and_old_files_remain_legacy():
    variant = gifts.Variant(
        {"buffer_code": CODE, "buffer_lead_1": CODE, "buffer_lead_2": CODE},
        {"buffer_ack_each_lead": True, "buffer_lead_expect": ACK, "buffer_expect": FINAL},
    )
    loaded = gifts.loads(gifts.dumps(gifts.Gift("frlg", "Acked", {"BPGD": variant})))
    chosen = gift_file.distribution(loaded.variants["BPGD"])
    assert chosen.buffer_ack_each_lead is True
    assert chosen.buffer_lead_expect == ACK
    assert chosen.buffer_expect == FINAL

    old = gifts.Gift(
        "frlg", "Legacy",
        {"BPGD": gifts.Variant({"buffer_code": CODE, "buffer_lead_1": CODE}, {})},
    )
    legacy = gift_file.distribution(gifts.loads(gifts.dumps(old)).variants["BPGD"])
    assert legacy.buffer_ack_each_lead is False
    assert legacy.buffer_lead_expect is None
