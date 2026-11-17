"""Two-request native MiniMax tool-call smoke test; no TV operations or sensitive content.
Reads the existing authorized credential from HKCU Environment. Never stores credentials
or raw reasoning/model responses. Writes only a sanitized evidence report.
"""
import datetime
import hashlib
import json
import pathlib
import time
import urllib.error
import urllib.request
import winreg

BASE = 'https://gateway.example.com'
MODEL = 'MiniMax-M3.1-Flash'
MAX_RESPONSE = 1024 * 1024
REPORT = pathlib.Path(__file__).with_name('minimax-native-tool-probe.json')

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

def run():
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r'Environment') as reg:
        key = winreg.QueryValueEx(reg, 'GATEWAY_API_KEY')[0]
    opener = urllib.request.build_opener(NoRedirect)
    tools = [{
        'type': 'function',
        'function': {
            'name': 'get_status',
            'description': 'Return a synthetic television status for this read-only integration test.',
            'parameters': {'type': 'object', 'properties': {}, 'required': [], 'additionalProperties': False}
        }
    }]
    messages = [
        {'role': 'system', 'content': 'This is a harmless API integration test with synthetic data. Call get_status exactly once, then report the returned volume in one short Chinese sentence. Do not infer the status before the tool returns.'},
        {'role': 'user', 'content': '请调用 get_status 查询这台合成测试电视的音量，然后简短回答。'}
    ]
    report = {'model': MODEL, 'endpoint': BASE + '/v1/chat/completions',
              'time': datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).isoformat(),
              'credential_logged': False, 'raw_reasoning_logged': False, 'rounds': []}
    def complete():
        body = {'model': MODEL, 'reasoning_effort': 'low', 'max_completion_tokens': 2048,
                'stream': False, 'messages': messages, 'tools': tools, 'tool_choice': 'auto'}
        request = urllib.request.Request(BASE + '/v1/chat/completions',
            data=json.dumps(body, ensure_ascii=False).encode('utf-8'),
            headers={'Authorization': 'Bearer ' + key, 'Content-Type': 'application/json'}, method='POST')
        start = time.monotonic()
        with opener.open(request, timeout=60) as response:
            raw = response.read(MAX_RESPONSE + 1)
            if len(raw) > MAX_RESPONSE: raise RuntimeError('response_size_limit')
            envelope = json.loads(raw)
        choice = envelope['choices'][0]
        msg = choice['message']
        report['rounds'].append({'http_status': 200, 'elapsed_seconds': round(time.monotonic() - start, 2),
            'finish_reason': choice.get('finish_reason'), 'assistant_fields': sorted(msg),
            'reasoning_content_present': 'reasoning_content' in msg,
            'reasoning_character_count': len(msg.get('reasoning_content') or ''),
            'tool_call_count': len(msg.get('tool_calls') or [])})
        if choice.get('finish_reason') not in ('stop', 'tool_calls'): raise RuntimeError('incomplete_response')
        return msg
    try:
        first = complete()
        calls = first.get('tool_calls') or []
        if len(calls) != 1 or calls[0].get('function', {}).get('name') != 'get_status':
            raise RuntimeError('expected_single_get_status')
        args = json.loads(calls[0]['function']['arguments'])
        if args != {}: raise RuntimeError('unexpected_tool_arguments')
        preserved = json.dumps(first, sort_keys=True, ensure_ascii=False)
        messages.append(first)  # Complete original assistant message, including reasoning_content.
        messages.append({'role': 'tool', 'tool_call_id': calls[0]['id'],
                         'content': json.dumps({'synthetic': True, 'volume': 19, 'muted': False})})
        if json.dumps(messages[-2], sort_keys=True, ensure_ascii=False) != preserved:
            raise RuntimeError('assistant_message_changed')
        report['full_assistant_message_preserved'] = True
        report['preserved_message_sha256'] = hashlib.sha256(preserved.encode()).hexdigest()
        second = complete()
        if second.get('tool_calls'): raise RuntimeError('unexpected_additional_tool_call')
        final = second.get('content') or ''
        # Never emit or save embedded reasoning if a provider puts it in content.
        if '<think' in final.lower(): raise RuntimeError('unexpected_thinking_in_visible_content')
        if '19' not in final: raise RuntimeError('missing_synthetic_tool_result')
        report['final_visible_answer'] = final[:500]
        report['passed'] = True
    except urllib.error.HTTPError as error:
        report['passed'] = False; report['failure'] = 'http_' + str(error.code)
    except Exception as error:
        report['passed'] = False
        report['failure'] = str(error) if isinstance(error, RuntimeError) else type(error).__name__
    REPORT.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if not report.get('passed'): raise SystemExit(1)

if __name__ == '__main__': run()
