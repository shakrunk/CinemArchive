-- Awards count (Wikidata P166) and Bechdel test result (Wikidata P5021/P9259), both
-- resolved via the media-proxy 'accolades' action.
alter table titles add column awards_count integer;
alter table titles add column bechdel_outcome text check (bechdel_outcome in ('pass', 'fail'));
alter table titles add column bechdel_score text;
