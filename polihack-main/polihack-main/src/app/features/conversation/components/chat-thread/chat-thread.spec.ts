import { ComponentFixture, TestBed } from '@angular/core/testing';

import { ChatThread } from './chat-thread';

describe('ChatThread', () => {
  let component: ChatThread;
  let fixture: ComponentFixture<ChatThread>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ChatThread],
    }).compileComponents();

    fixture = TestBed.createComponent(ChatThread);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
