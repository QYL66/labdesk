-- LabDesk intents and sample questions. Apply after schema_pg.sql and init_data_pg.sql.
INSERT INTO t_intent_node (id, intent_code, name, level, description, mcp_tool_id, require_confirm, kind, sort_order, enabled) VALUES
    ('2001523723396310101', 'lab_date', '当前日期', 0, '查询北京时间的当前日期，用于换算今天、明天等相对日期', 'current_date', 0, 2, 10, 1),
    ('2001523723396310102', 'lab_availability', '设备空闲时段', 0, '按指定日期查询设备、房间和可预约整点，可选设备编号', 'lab_list_availability', 0, 2, 20, 1),
    ('2001523723396310103', 'lab_book', '预约实验设备', 0, '确认后为当前登录用户预约一小时设备，成功时返回实际预约编号', 'lab_book_equipment', 1, 2, 30, 1),
    ('2001523723396310104', 'lab_my_bookings', '我的设备预约', 0, '查询当前登录用户最近的设备预约', 'lab_list_my_bookings', 0, 2, 40, 1)
ON CONFLICT (id) DO NOTHING;

INSERT INTO t_sample_question (id, title, description, question) VALUES
    ('2001523723396310201', '显微镜归位', '查阅设备操作规程', '使用教学显微镜结束后，归位需要贴什么颜色的复位卡？请附出处。'),
    ('2001523723396310202', '设备空闲', '查看明天的可预约时段', '明天有哪些设备可以预约？'),
    ('2001523723396310203', '我的预约', '查看我的设备预约记录', '查一下我有哪些设备预约。')
ON CONFLICT (id) DO NOTHING;
