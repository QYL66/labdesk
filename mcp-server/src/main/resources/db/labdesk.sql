CREATE TABLE IF NOT EXISTS labdesk_equipment (
    code text PRIMARY KEY,
    name text NOT NULL,
    room text NOT NULL
);

CREATE TABLE IF NOT EXISTS labdesk_booking (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    equipment_code text NOT NULL REFERENCES labdesk_equipment(code),
    starts_at timestamptz NOT NULL,
    user_id varchar(64) NOT NULL,
    UNIQUE (equipment_code, starts_at)
);

CREATE INDEX IF NOT EXISTS labdesk_booking_user_start ON labdesk_booking (user_id, starts_at);

INSERT INTO labdesk_equipment (code, name, room) VALUES
    ('SCOPE-01', '教学显微镜', '演示楼 A201'),
    ('BENCH-02', '电子测量台', '演示楼 A202'),
    ('PRINT-03', '桌面 3D 打印机', '演示楼 B101')
ON CONFLICT (code) DO NOTHING;
