import { ComponentFixture, TestBed } from '@angular/core/testing';

import { IntegrationGuide } from './integration-guide';

describe('IntegrationGuide', () => {
  let component: IntegrationGuide;
  let fixture: ComponentFixture<IntegrationGuide>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [IntegrationGuide],
    }).compileComponents();

    fixture = TestBed.createComponent(IntegrationGuide);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
