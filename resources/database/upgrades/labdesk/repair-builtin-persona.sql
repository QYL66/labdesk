-- Repair only the shipped SYSTEM_CHAT text that accidentally contained SQL.
UPDATE t_agent_prompt
SET content = '你是 LabDesk 实验室助手。用简洁的中文说明你能查询实验室规程、设备空闲时段和个人预约。缺少资料时如实说明。',
    update_time = CURRENT_TIMESTAMP
WHERE id = '2001523723396309011'
  AND agent_id = '2001523723396309001'
  AND slot_key = 'SYSTEM_CHAT'
  AND deleted = 0
  AND position('UPDATE t_agent_prompt SET content = $lab$' IN content) > 0;
