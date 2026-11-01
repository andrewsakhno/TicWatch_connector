-- Which children's accounts a parent device exchanges data for; set by hand in the admin page.
CREATE TABLE parent_account (
    device_id  TEXT NOT NULL REFERENCES device (device_id) ON DELETE CASCADE,
    account_id TEXT NOT NULL REFERENCES account (account_id) ON DELETE CASCADE,
    PRIMARY KEY (device_id, account_id)
);

-- Parents so far could publish for any account, so each keeps the ones it already published for.
INSERT INTO parent_account (device_id, account_id)
SELECT DISTINCT state.owner_device_id, state.account_id
FROM topic_state state
JOIN device owner ON owner.device_id = state.owner_device_id
WHERE owner.role = 'PARENT';
