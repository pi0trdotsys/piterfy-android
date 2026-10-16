package com.eddyizm.tempus.repository;
import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import com.eddyizm.tempus.App;
import com.eddyizm.tempus.database.AppDatabase;
import com.eddyizm.tempus.database.dao.RecentSearchDao;
import com.eddyizm.tempus.subsonic.Subsonic;
import com.eddyizm.tempus.subsonic.api.searching.SearchingClient;
import com.eddyizm.tempus.subsonic.base.ApiResponse;
import org.junit.*;
import org.mockito.*;
import retrofit2.*;
import static org.mockito.Mockito.*;
import static org.junit.Assert.*;
public class PiterfySearchingRepositoryTest {
 @Rule public InstantTaskExecutorRule instant = new InstantTaskExecutorRule();
 @Test public void supersededSearchCancelsNetworkAndIgnoresItsFailure() {
  try(MockedStatic<AppDatabase> dbStatic=mockStatic(AppDatabase.class);MockedStatic<App> appStatic=mockStatic(App.class)) {
   AppDatabase db=mock(AppDatabase.class);dbStatic.when(AppDatabase::getInstance).thenReturn(db);
   when(db.recentSearchDao()).thenReturn(mock(RecentSearchDao.class));
   Subsonic subsonic=mock(Subsonic.class); SearchingClient client=mock(SearchingClient.class);
   appStatic.when(()->App.getSubsonicClientInstance(false)).thenReturn(subsonic);when(subsonic.getSearchingClient()).thenReturn(client);
   Call<ApiResponse> first=mock(Call.class),second=mock(Call.class);
   when(client.search3("old",50,0,20,0,20,0)).thenReturn(first);
   when(client.search3("new",50,0,20,0,20,0)).thenReturn(second);
   SearchingRepository repository=new SearchingRepository();
   repository.search3("old");
   ArgumentCaptor<Callback<ApiResponse>> callback=ArgumentCaptor.forClass(Callback.class);
   verify(first).enqueue(callback.capture());
   repository.search3("new");verify(first).cancel();
   callback.getValue().onFailure(first,new java.io.IOException("stale"));
   assertEquals(false,repository.error.getValue());
   repository.close();verify(second).cancel();
   // No full-library pagination request until the play-all action.
   verify(client,never()).search3(anyString(),eq(200),anyInt(),eq(0),eq(0),eq(0),eq(0));
  }
 }
}
