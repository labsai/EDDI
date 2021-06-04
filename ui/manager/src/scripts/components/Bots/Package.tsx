import * as _ from 'lodash';
import * as moment from 'moment';
import Radium from 'radium';
import * as React from 'react';
import { connect } from 'react-redux';
import ClipLoader from 'react-spinners/ClipLoader';
import { compose, pure, setDisplayName } from 'recompose';
import { BLUE_COLOR } from '../../../styles/DefaultStylingProperties';
import eddiApiActionDispatchers from '../../actions/EddiApiActionDispatchers';
import ModalActionDispatchers from '../../actions/ModalActionDispatchers';
import { historyPush } from '../../history';
import { readOnlySelector } from '../../selectors/AuthenticationSelectors';
import { packageSelector } from '../../selectors/PackageSelectors';
import WhiteButton from '../Assets/Buttons/WhiteButton';
import { IBot, IPackage } from '../utils/AxiosFunctions';
import styles from './Package.styles';

interface IPrivateProps extends IPublicProps {
  packagePayload: IPackage;
  isLoading: boolean;
  error: Error;
  isLoadingPackageData: boolean;
}

interface IPublicProps {
  packageResource: string;
  bot: IBot;
  readOnly?: boolean;
}

interface IState {
  showModal: boolean;
}

class Package extends React.Component<IPrivateProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      showModal: false,
    };
  }

  async componentDidMount() {
    if (_.isEmpty(this.props.packagePayload)) {
      eddiApiActionDispatchers.fetchPackageAction(this.props.packageResource);
    }
  }

  openViewJsonModal = () => {
    ModalActionDispatchers.showViewJsonModal(this.props.packageResource);
  };

  getPackageNameStyling() {
    if (
      this.props.packagePayload.version <
      this.props.packagePayload.currentVersion
    ) {
      return { ...styles.botPackageName, ...styles.hasNewVersion };
    } else {
      return styles.botPackageName;
    }
  }

  getPackageModifiedOnStyling() {
    if (
      this.props.packagePayload.version <
      this.props.packagePayload.currentVersion
    ) {
      return { ...styles.botPackageLastModifiedOn, ...styles.hasNewVersion };
    } else {
      return styles.botPackageLastModifiedOn;
    }
  }

  render() {
    const packageHasNewVersion = this.props.packagePayload
      ? this.props.packagePayload.version <
        this.props.packagePayload.currentVersion
      : false;
    return (
      <div>
        {!this.props.isLoading && (
          <div>
            {!!this.props.error && <p>{'Error: Could not load package'}</p>}
            {!this.props.error && _.isEmpty(this.props.packagePayload) && (
              <ClipLoader color={BLUE_COLOR} />
            )}
            {!this.props.error && !_.isEmpty(this.props.packagePayload) && (
              <div>
                <button
                  onClick={() =>
                    historyPush(
                      `/packageview/${this.props.packagePayload.id}`,
                      packageHasNewVersion
                        ? [`version=${this.props.packagePayload.version}`]
                        : [],
                    )
                  }
                  style={styles.botPackageButton}>
                  <div style={this.getPackageNameStyling()}>
                    {this.props.packagePayload.name ||
                      this.props.packagePayload.id}
                  </div>
                  <div style={this.getPackageModifiedOnStyling()}>
                    {moment(this.props.packagePayload.lastModifiedOn).format(
                      'DD.MM.YYYY',
                    )}
                  </div>
                </button>
                {packageHasNewVersion && (
                  <WhiteButton
                    text={'Update'}
                    customStyles={styles.updatePackage}
                    onClick={() =>
                      eddiApiActionDispatchers.updateBotAction(
                        this.props.bot,
                        this.props.packagePayload,
                      )
                    }
                    disabled={this.props.readOnly}
                  />
                )}
              </div>
            )}
          </div>
        )}
      </div>
    );
  }
}

const ComposedPackage: React.ComponentClass<IPublicProps> = compose<
  IPrivateProps,
  IPublicProps
>(
  pure,
  connect(packageSelector),
  connect(readOnlySelector),
  Radium,
  setDisplayName('Package'),
)(Package);

export default ComposedPackage;
