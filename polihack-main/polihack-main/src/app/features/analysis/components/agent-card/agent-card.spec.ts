import { ComponentFixture, TestBed } from '@angular/core/testing';

import { AgentCard } from './agent-card';

describe('AgentCard', () => {
  let component: AgentCard;
  let fixture: ComponentFixture<AgentCard>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AgentCard],
    }).compileComponents();

    fixture = TestBed.createComponent(AgentCard);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
