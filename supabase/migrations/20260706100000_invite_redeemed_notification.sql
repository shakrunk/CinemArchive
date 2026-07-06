-- ============================================================
-- Notify an inviter when their invite code is redeemed
-- ============================================================
--
-- Every other action in the sharing feature (friend request, comment,
-- reaction, share-link view, recommendation) produces a notification;
-- invite redemption didn't, even though it's the one action performed by
-- redeem-invite's service-role client rather than a SECURITY DEFINER RPC.
-- Widen the type constraint and let that function insert directly.

alter table notifications drop constraint notifications_type_check;
alter table notifications add constraint notifications_type_check check (type in (
  'friend_request_received', 'friend_request_accepted',
  'share_link_used', 'recommendation_received',
  'comment_received', 'reaction_received', 'invite_redeemed'
));
